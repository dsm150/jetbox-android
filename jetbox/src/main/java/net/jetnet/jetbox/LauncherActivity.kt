package net.jetnet.jetbox

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Ярлык JetBox на рабочем столе — трамплин без содержимого (окно не рисует: закрывается в
 * `onCreate`, заставка системы переходит к [JetBoxActivity]). Задачи нет — открывает [JetBoxActivity];
 * задача уже есть — система положила нас поверх неё, и мы просто закрываемся: клиент видит то,
 * что оставил (в том числе экраны NetBird расширенного режима). Ярлык прямо на `singleTask`-окне
 * снимал бы всё, что открыто над ним.
 */
class LauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isTaskRoot) {
            startActivity(Intent(this, JetBoxActivity::class.java).setAction(Intent.ACTION_MAIN))
        }
        finish()
    }
}
