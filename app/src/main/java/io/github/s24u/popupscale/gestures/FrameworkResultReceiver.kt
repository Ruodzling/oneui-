package io.github.s24u.popupscale.gestures

import android.os.Parcel
import android.os.ResultReceiver

/** Another process cannot resolve an anonymous subclass loaded only by an LSPosed loader. */
object FrameworkResultReceiver {
    @JvmStatic fun proxy(receiver:ResultReceiver):ResultReceiver {
        val parcel=Parcel.obtain()
        return try {
            receiver.writeToParcel(parcel,0)
            parcel.setDataPosition(0)
            ResultReceiver.CREATOR.createFromParcel(parcel)
        }finally{parcel.recycle()}
    }
}
