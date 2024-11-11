package edu.gatech.cc.cellwatch.msak_tester

import android.app.Application
import org.conscrypt.Conscrypt
import java.security.Security

class MsakAndroidTestApp: Application() {
    init {
        // Using Conscrypt somehow makes the msak CountableSocket work with TLS sockets --
        // it doesn't otherwise. I found a project on GitHub trying to count socket bytes
        // (https://github.com/dave-r12/okhttp-byte-counter) and then found a linked
        // issue (https://github.com/google/conscrypt/issues/65) that suggests Conscrypt
        // might eventually solve the problem but hasn't yet. I guess it has now...
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }
}