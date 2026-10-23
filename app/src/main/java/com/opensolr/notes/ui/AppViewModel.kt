package com.opensolr.notes.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opensolr.notes.auth.OpensolrAuth
import com.opensolr.notes.data.AppPrefs
import com.opensolr.notes.data.Note
import com.opensolr.notes.data.NotesDb
import com.opensolr.notes.index.NoteSync
import com.opensolr.notes.index.NotesIndex
import com.opensolr.notes.net.IndexChoiceException
import com.opensolr.notes.net.OpensolrApi
import com.opensolr.notes.net.SelfUpdate
import com.opensolr.notes.net.SignInRequiredException
import com.opensolr.notes.net.UpdateCheck
import com.opensolr.notes.search.Filters
import com.opensolr.notes.search.Hit
import com.opensolr.notes.search.NotesSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen {
    object Setup : Screen()
    object Notes : Screen()
    data class Editor(val id: String?) : Screen()
    object Settings : Screen()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = AppPrefs(app)
    val db = NotesDb(app)
    private val api = OpensolrApi(prefs)
    private val index = NotesIndex(app)
    private val sync = NoteSync(app)
    private val searcher = NotesSearch(prefs, api)

    var signedIn by mutableStateOf(prefs.signedIn); private set
    var screen by mutableStateOf<Screen>(if (prefs.signedIn) Screen.Notes else Screen.Setup); private set
    var notes by mutableStateOf<List<Note>>(emptyList()); private set
    var categories by mutableStateOf<List<String>>(emptyList()); private set
    var filters by mutableStateOf(Filters()); private set
    var query by mutableStateOf(""); private set
    var hits by mutableStateOf<List<Hit>?>(null); private set
    var searching by mutableStateOf(false); private set
    var syncing by mutableStateOf(false); private set
    /** A spinner over the whole screen while an action runs (opening, saving, deleting a note). */
    var busy by mutableStateOf(false); private set
    var problem by mutableStateOf<String?>(null); private set
    var update by mutableStateOf<UpdateCheck.Update?>(null); private set
    var updateProgress by mutableStateOf<Int?>(null); private set
    var updateError by mutableStateOf<String?>(null); private set
    /** Notes indexes of other phones found on the account; the reader picks one or starts fresh. */
    var indexChoices by mutableStateOf<List<String>?>(null); private set

    init {
        reload()
        viewModelScope.launch { AppPrefs.sessionEnded.collect { if (it > 0) onSessionEnded() } }
        if (prefs.signedIn) syncNow()
    }

    fun go(s: Screen) { screen = s }

    /** The editor for one note (or a new one): the spinner shows until the editor is on screen. */
    fun open(id: String?) { busy = true; screen = Screen.Editor(id) }

    fun ready() { busy = false }

    fun back() {
        screen = if (signedIn) Screen.Notes else Screen.Setup
    }

    fun reload() {
        val f = filters
        viewModelScope.launch(Dispatchers.IO) {
            val list = db.all(f.category, f.from, f.to)
            val cats = db.categories()
            withContext(Dispatchers.Main) { notes = list; categories = cats }
        }
    }

    fun applyFilters(f: Filters) {
        filters = f
        reload()
        if (query.isNotBlank()) search(query)
    }

    private var searchJob: Job? = null

    fun search(typed: String) {
        query = typed
        searchJob?.cancel()
        if (typed.isBlank()) { hits = null; searching = false; return }
        searchJob = viewModelScope.launch(Guard) {
            delay(250)
            searching = true
            try {
                val connection = withContext(Dispatchers.IO) { index.ensure() }
                hits = withContext(Dispatchers.IO) { searcher.search(connection, typed, filters) }
            } catch (e: SignInRequiredException) {
                onSessionEnded()
            } catch (e: IndexChoiceException) {
                indexChoices = e.choices
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message
            } finally {
                searching = false
            }
        }
    }

    /** The notes a search found, in the order the index ranked them, with their snippets. */
    fun found(): List<Pair<Note, String>> {
        val h = hits ?: return emptyList()
        val byId = db.byIds(h.map { it.id })
        return h.mapNotNull { hit -> byId[hit.id]?.takeIf { !it.deleted }?.let { it to hit.snippet } }
    }

    /** Writes the note, then goes back to the list; the spinner covers the write. */
    fun save(id: String?, title: String, text: String, category: String) {
        busy = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.save(id, title, text, category) }
            reload()
            back()
            busy = false
            syncNow()
        }
    }

    /** A note dragged to another place: this phone's order, kept across restarts, never sent to the index. */
    fun move(fromId: String, toId: String) {
        val ids = notes.map { it.id }.toMutableList()
        val from = ids.indexOf(fromId); val to = ids.indexOf(toId)
        if (from < 0 || to < 0 || from == to) return
        ids.add(to, ids.removeAt(from))
        val byId = notes.associateBy { it.id }
        db.setOrder(ids)
        notes = ids.map { byId.getValue(it) }
        reload()
    }

    fun delete(id: String) {
        busy = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.delete(id) }
            reload()
            back()
            busy = false
            syncNow()
        }
    }

    fun syncNow() {
        if (!prefs.signedIn || syncing) return
        viewModelScope.launch(Guard) {
            syncing = true
            try {
                val p = withContext(Dispatchers.IO) { sync.run() }
                problem = p
                reload()
            } catch (e: SignInRequiredException) {
                onSessionEnded()
            } catch (e: IndexChoiceException) {
                indexChoices = e.choices
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message
            } finally {
                syncing = false
            }
        }
    }

    fun clearProblem() { problem = null }

    fun chooseIndex(existing: String?) {
        indexChoices = null
        index.choose(existing)
        syncNow()
    }

    // ---- account ----

    fun startSignIn(context: Context) = OpensolrAuth.start(context, prefs)

    fun onAuthCallback(uri: Uri) {
        if (!OpensolrAuth.isCallback(uri)) return
        viewModelScope.launch(Guard) {
            try {
                OpensolrAuth.finish(getApplication(), uri)
                signedIn = prefs.signedIn
                screen = Screen.Notes
                withContext(Dispatchers.IO) { runCatching { api.upgradeToDeviceKey() } }
                syncNow()
            } catch (e: Exception) {
                problem = e.message
            }
        }
    }

    fun signOut() {
        prefs.clearSession()
        index.forget()
        signedIn = false
        screen = Screen.Setup
    }

    private fun onSessionEnded() {
        index.forget()
        signedIn = false
        screen = Screen.Setup
    }

    /** search.opensolr.com/<index>: the hosted page, behind the index's own user and password. */
    fun searchPageUrl(): String? = prefs.indexName.takeIf { it.isNotBlank() }?.let { "https://search.opensolr.com/$it" }

    // ---- updates ----

    fun checkForUpdate() {
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < 6 * 3600_000L && update == null) return
        viewModelScope.launch(Guard) {
            prefs.lastUpdateCheck = System.currentTimeMillis()
            UpdateCheck.check().getOrNull()?.let { update = it }
        }
    }

    fun installUpdate(context: Context) {
        val u = update ?: return
        viewModelScope.launch(Guard) {
            updateError = null
            updateProgress = 0
            when (val o = SelfUpdate.downloadAndInstall(context, u.apkUrl) { updateProgress = it }) {
                is SelfUpdate.Outcome.NeedsPermission -> { updateProgress = null; SelfUpdate.openInstallPermission(context) }
                is SelfUpdate.Outcome.Failed -> { updateProgress = null; updateError = o.message }
                is SelfUpdate.Outcome.Invalid -> { updateProgress = null; updateError = "The download was not a valid update" }
                is SelfUpdate.Outcome.Started -> updateProgress = null
            }
        }
    }
}
