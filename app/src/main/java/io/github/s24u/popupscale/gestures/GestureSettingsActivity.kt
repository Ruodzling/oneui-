package io.github.s24u.popupscale.gestures

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.s24u.popupscale.MainActivity

/** Retain the old internal entry point while all settings share the Material shell. */
class GestureSettingsActivity:Activity(){
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        startActivity(Intent(this,MainActivity::class.java).putExtra("tab",2))
        finish()
    }
}
