package de.equinox.v05

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.rgb(16, 18, 22))
        }
        root.addView(TextView(this).apply {
            text = "EQUINOX v0.5"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "STEP 01 OK\nBasis-App läuft."
            textSize = 18f
            setTextColor(Color.rgb(53, 214, 200))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })
        setContentView(root)
    }
}
