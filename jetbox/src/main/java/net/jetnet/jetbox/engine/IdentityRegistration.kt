package net.jetnet.jetbox.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Регистрация с новой личностью устройства (новый ключ WireGuard) — последовательность шагов без
 * Android: вход ядра ([register] `login`) и хранилище (`onRegistered`) подставляются снаружи
 * (`AndroidEngine.register`, в тестах — `FakeEngine`).
 *
 * 1. **Запуск ядра закрыт** ([TunnelGate.close]) на всё время замены: плитка, виджет, always-on и
 *    MDM-перезапуск идут в ядро мимо `AndroidEngine` (врезка в `EngineRunner.runClient`). Ядро уже
 *    работает — отказ [EngineStillRunning] до любых файлов: файлы профиля нельзя менять под ним.
 * 2. **Остатки прошлого раза** — [recover] (обычно их уже разобрал старт процесса).
 * 3. **stash** — метка с отпечатком нового setup key, прежние файлы в сторону.
 * 4. **Вход** (`login`: адрес сервера, `NewAuth` — новый конфиг с новым ключом, вход по ключу).
 * 5. **Хранилище** (`onRegistered` — `store.remember` с отпечатком), затем **commit**. Хвост 5 —
 *    в `NonCancellable`: отмена между записью хранилища и фиксацией файлов не разводит их.
 *
 * Точка фиксации — запись хранилища: после неё личность новая, даже если процесс умрёт до commit
 * (старт процесса увидит метку с тем же отпечатком, что в хранилище, и доделает commit). До неё —
 * прежняя: любая неудача, отмена или смерть процесса → [ProfileFilesSwap.restore].
 *
 * Не удалось вернуть прежние файлы — [RestoreFailed]: туннель поднимать нельзя (на диске ни та, ни
 * другая личность), повтор начнёт с [recover], то есть снова с возврата.
 */
class IdentityRegistration(
    private val swap: ProfileFilesSwap,
    private val gate: TunnelGate,
    private val log: (String) -> Unit = {},
) {
    /** Запуск ядра на время замены (у `AndroidEngine` — флаг процесса в `VPNService`). */
    interface TunnelGate {
        fun close()
        fun open()

        /** Работает ли ядро — проверяется после [close], поэтому новый запуск уже не вклинится. */
        fun isEngineRunning(): Boolean
    }

    /** Ядро работает — менять файлы профиля нельзя. Прежний туннель не тронут. */
    class EngineStillRunning : IllegalStateException("туннель ещё работает — замена подключения отложена")

    /** Прежние файлы не вернулись — на диске ни прежняя, ни новая личность. */
    class RestoreFailed(cause: Throwable) :
        IOException("не удалось вернуть прежнее подключение: ${cause.message}", cause)

    enum class Recovery { NONE, COMMITTED, RESTORED }

    /**
     * @param newFingerprint отпечаток setup key новой ссылки (`JetBoxIdentity.fingerprint`).
     * @param storedFingerprint отпечаток в хранилище сейчас — для разбора остатков.
     * @param login вход ядра; `null` — успех, иначе ошибка ядра. Отмена — `CancellationException`.
     * @param onRegistered запись хранилища; вызывается ровно один раз и только после успешного входа.
     */
    suspend fun register(
        newFingerprint: String,
        storedFingerprint: String?,
        login: suspend () -> Exception?,
        onRegistered: () -> Unit,
    ): Result<Unit> {
        gate.close()
        try {
            if (gate.isEngineRunning()) {
                log("замена подключения: ядро ещё работает, файлы не трогаем")
                return Result.failure(EngineStillRunning())
            }
            try {
                recover(swap, storedFingerprint)?.let { log("замена подключения: остатки прошлой попытки — $it") }
            } catch (e: Exception) {
                return Result.failure(RestoreFailed(e))
            }
            return swapAndLogin(newFingerprint, login, onRegistered)
        } finally {
            gate.open()
        }
    }

    /**
     * Разбор остатков вне замены — путь прежнего ключа (`AndroidEngine.registerKeepingKey`) — на тех
     * же условиях, что [register]: запуск ядра закрыт на время разбора, ядро работает —
     * [EngineStillRunning] до любых файлов. Остатков нет — запуск ядра не трогается.
     * @return что сделано (`null` — разбирать нечего); сбой разбора — [RestoreFailed].
     */
    fun recoverGuarded(storedFingerprint: String?): Result<Recovery?> {
        if (!swap.hasLeftovers()) return Result.success(null)
        gate.close()
        try {
            if (gate.isEngineRunning()) {
                log("остатки замены подключения: ядро ещё работает, файлы не трогаем")
                return Result.failure(EngineStillRunning())
            }
            return try {
                Result.success(recover(swap, storedFingerprint).also { log("остатки замены подключения — $it") })
            } catch (e: Exception) {
                Result.failure(RestoreFailed(e))
            }
        } finally {
            gate.open()
        }
    }

    /**
     * «Выйти»: стереть файлы профиля на тех же условиях, что замена, — запуск ядра закрыт, ядро
     * работает — отказ [EngineStillRunning]. Не стёрлось — `IOException` из [ProfileFilesSwap.wipe].
     */
    fun forget(): Result<Unit> {
        gate.close()
        try {
            if (gate.isEngineRunning()) {
                log("выход: ядро ещё работает, файлы не трогаем")
                return Result.failure(EngineStillRunning())
            }
            return try {
                swap.wipe()
                log("выход: файлы профиля стёрты")
                Result.success(Unit)
            } catch (e: IOException) {
                Result.failure(e)
            }
        } finally {
            gate.open()
        }
    }

    private suspend fun swapAndLogin(
        newFingerprint: String,
        login: suspend () -> Exception?,
        onRegistered: () -> Unit,
    ): Result<Unit> {
        var remembered = false
        val failure: Exception = try {
            swap.stash(newFingerprint)
            log("замена подключения: прежние файлы отложены, вход с новой личностью")
            val error = login()
            if (error == null) {
                withContext(NonCancellable) {
                    onRegistered()
                    remembered = true
                    commitOrLeaveForStartup()
                }
                return Result.success(Unit)
            }
            error
        } catch (e: CancellationException) {
            // Сценарий отменён: хранилище не тронуто — возвращаем прежнее и пробрасываем отмену.
            // Возврат не удался — остатки на диске: сценарий видит их (`EngineApi.hasUnfinishedSwap`)
            // и прежний туннель не поднимает.
            if (!remembered) withContext(NonCancellable) { rollBack() }
            throw e
        } catch (e: Exception) {
            if (remembered) return Result.success(Unit) // хранилище уже новое — откатывать нельзя
            e
        }
        return rollBack()?.let { Result.failure(it) } ?: Result.failure(failure)
    }

    /** `null` — прежние файлы на месте; иначе [RestoreFailed]. */
    private fun rollBack(): RestoreFailed? = try {
        swap.restore()
        log("замена подключения: прежняя личность устройства возвращена")
        null
    } catch (e: Exception) {
        log("замена подключения: прежние файлы не вернулись: ${e.message}")
        RestoreFailed(e)
    }

    /**
     * Хранилище уже новое: копии не удалились — не беда, метка осталась и совпадает с хранилищем,
     * старт процесса (или следующая регистрация) доделает commit, а не вернёт прежний ключ.
     */
    private fun commitOrLeaveForStartup() {
        try {
            swap.commit()
        } catch (e: Exception) {
            log("замена подключения: копии прежних файлов не удалены (${e.message}), доделает следующий старт")
        }
    }

    companion object {
        /**
         * Разбор остатков оборванной замены (смерть процесса, сбой отката). Метка с тем же
         * отпечатком, что в хранилище, — замена удалась до записи хранилища включительно → commit;
         * иначе (другой отпечаток, метки нет, хранилище пусто) → restore. Сбой — исключение.
         * @return что сделано; `null` — разбирать было нечего.
         */
        @Throws(IOException::class)
        fun recover(swap: ProfileFilesSwap, storedFingerprint: String?): Recovery? {
            if (!swap.hasLeftovers()) return null
            val pending = swap.pendingFingerprint()
            return if (pending != null && pending == storedFingerprint) {
                swap.commit()
                Recovery.COMMITTED
            } else {
                swap.restore()
                Recovery.RESTORED
            }
        }
    }
}
