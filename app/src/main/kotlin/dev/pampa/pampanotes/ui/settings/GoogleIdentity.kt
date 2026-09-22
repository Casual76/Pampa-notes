package dev.pampa.pampanotes.ui.settings

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * L'accesso con Google: un ID token dal Credential Manager, da consegnare al Worker.
 *
 * Il token dura un'ora e non e' quello che l'app tiene: il Worker lo verifica e apre una
 * sessione, e da li' in poi l'app manda il token di sessione. Serve solo la prima volta su ogni
 * dispositivo, e per questo si chiede senza filtrare sugli account gia' usati: e' la prima volta
 * per definizione.
 *
 * Il `context` deve essere quello di un'Activity: il Credential Manager apre un foglio.
 */
object GoogleIdentity {

  class Cancelled : Exception("accesso annullato")

  suspend fun idToken(context: Context, clientId: String): String {
    require(clientId.isNotBlank()) { "client ID di Google mancante" }
    val option = GetGoogleIdOption.Builder()
      .setServerClientId(clientId)
      .setFilterByAuthorizedAccounts(false)
      .setAutoSelectEnabled(false)
      .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val result = try {
      CredentialManager.create(context).getCredential(context, request)
    } catch (cancelled: GetCredentialCancellationException) {
      throw Cancelled()
    } catch (error: GetCredentialException) {
      throw IllegalStateException(error.message ?: error.type, error)
    }
    val credential = result.credential
    if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
      return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
    throw IllegalStateException("credenziale inattesa: ${credential.type}")
  }
}
