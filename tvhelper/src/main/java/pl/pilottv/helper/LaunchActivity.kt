package pl.pilottv.helper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Obsługuje link pilottv://launch?app=nazwa.pakietu i uruchamia tę aplikację.
 * Usługa pilota Android TV umie tylko otwierać linki, a część aplikacji (SkyShowtime, Luna) ich nie obsługuje.
 */
class LaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent?.data?.getQueryParameter("app")
        val launch = pkg?.let {
            packageManager.getLeanbackLaunchIntentForPackage(it) ?: packageManager.getLaunchIntentForPackage(it)
        }
        if (launch != null) {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Toast.makeText(this, "Brak aplikacji: $pkg", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
