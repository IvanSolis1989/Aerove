package dev.aerove.lite

import dev.aerove.lite.flutter.MeowFlutterHost
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

/** Thin product host; the Meow bridge owns platform channel registration. */
class FlutterMainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MeowFlutterHost.attach(this, flutterEngine)
    }
}
