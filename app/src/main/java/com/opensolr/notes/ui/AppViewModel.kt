package com.opensolr.notes.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opensolr.notes.R
import com.opensolr.notes.auth.OpensolrAuth
import com.opensolr.notes.data.AppPrefs
import com.opensolr.notes.data.Note
import com.opensolr.notes.data.NotesDb
import com.opensolr.notes.index.NoteSync
import com.opensolr.notes.index.NotesIndex
import com.opensolr.notes.net.OpensolrApi
import com.opensolr.notes.net.SelfUpdate
import com.opensolr.notes.net.SignInRequiredException
import com.opensolr.notes.net.UpdateCheck
import com.opensolr.notes.search.AiPrompt
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
    var filters by mutableStateOf(Filters()); private set
    var query by mutableStateOf(""); private set
    var hits by mutableStateOf<List<Hit>?>(null); private set
    /** No page left to load: the list, or the results, are complete. */
    var notesEnd by mutableStateOf(false); private set
    var hitsEnd by mutableStateOf(false); private set
    private var loadingMore = false
    var searching by mutableStateOf(false); private set
    var syncing by mutableStateOf(false); private set
    /** A spinner over the whole screen while an action runs (opening, saving, deleting a note). */
    var busy by mutableStateOf(false); private set
    var problem by mutableStateOf<String?>(null); private set
    var update by mutableStateOf<UpdateCheck.Update?>(null); private set
    var updateProgress by mutableStateOf<Int?>(null); private set
    var updateError by mutableStateOf<String?>(null); private set
    /** AI Hints over the search results: null until asked, then the answer as it streams in. */
    var aiText by mutableStateOf<String?>(null); private set
    var aiRunning by mutableStateOf(false); private set
    private var aiJob: Job? = null

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

    /** The list again, as many rows as were on screen (at least a page), so the position holds. */
    fun reload() {
        val f = filters
        val want = maxOf(NotesDb.PAGE, notes.size)
        viewModelScope.launch(Dispatchers.IO) {
            val list = db.all(f.from, f.to, limit = want)
            withContext(Dispatchers.Main) { notes = list; notesEnd = list.size < want }
        }
    }

    /** The next page of notes, when the reader nears the end of the list. */
    fun loadMoreNotes() {
        if (notesEnd || loadingMore) return
        loadingMore = true
        val f = filters
        val offset = notes.size
        viewModelScope.launch(Dispatchers.IO) {
            val page = db.all(f.from, f.to, limit = NotesDb.PAGE, offset = offset)
            withContext(Dispatchers.Main) {
                if (notes.size == offset) { notes = notes + page; notesEnd = page.size < NotesDb.PAGE }
                loadingMore = false
            }
        }
    }

    /** The next page of results, from the index, when the reader nears the end. */
    fun loadMoreHits() {
        val h = hits ?: return
        if (hitsEnd || searching || query.isBlank()) return
        searchJob = viewModelScope.launch(Guard) {
            searching = true
            try {
                val connection = withContext(Dispatchers.IO) { index.ensure() }
                val page = withContext(Dispatchers.IO) { searcher.search(connection, query, filters, start = h.size) }
                hits = h + page
                hitsEnd = page.size < NotesSearch.PAGE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message
            } finally {
                searching = false
            }
        }
    }

    fun applyFilters(f: Filters) {
        filters = f
        reload()
        if (query.isNotBlank()) search(query)
    }

    private var searchJob: Job? = null

    /** What is typed in the search box; the search itself runs on Enter or the check button. */
    fun type(typed: String) {
        query = typed
        if (typed.isBlank()) { searchJob?.cancel(); stopAi(); hits = null; hitsEnd = false; searching = false }
    }

    fun search(typed: String = query) {
        query = typed
        searchJob?.cancel()
        stopAi()
        hitsEnd = false
        if (typed.isBlank()) { hits = null; searching = false; return }
        searchJob = viewModelScope.launch(Guard) {
            searching = true
            try {
                val connection = withContext(Dispatchers.IO) { index.ensure() }
                val page = withContext(Dispatchers.IO) { searcher.search(connection, typed, filters) }
                hits = page
                hitsEnd = page.size < NotesSearch.PAGE
            } catch (e: SignInRequiredException) {
                onSessionEnded()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message
            } finally {
                searching = false
            }
        }
    }

    /** AI Hints: the question is the search, the documents are the notes it found, the answer streams in. */
    fun askAi() {
        val h = hits ?: return
        val question = query.trim()
        if (question.isBlank() || h.isEmpty()) return
        aiJob?.cancel()
        aiText = ""
        aiRunning = true
        val ctx = getApplication<Application>()
        aiJob = viewModelScope.launch(Guard) {
            val me = coroutineContext[Job]
            try {
                val notes = withContext(Dispatchers.IO) { db.byIds(h.take(AiPrompt.TOP_N).map { it.id }) }
                val instruction = AiPrompt.instruction(AiPrompt.context(h, notes), question)
                val raw = StringBuilder()
                api.aiAnswer(prefs.indexName, instruction) { chunk ->
                    if (aiJob !== me) return@aiAnswer
                    raw.append(chunk)
                    aiText = raw.toString()
                }
                if (aiJob === me && raw.isBlank()) aiText = ctx.getString(R.string.ai_no_answer)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.opensolr.notes.net.QuotaExceededException) {
                aiText = ctx.getString(R.string.ai_off_quota)
            } catch (e: com.opensolr.notes.net.VectorNotAllowedException) {
                prefs.vectorAllowed = false
                aiText = ctx.getString(R.string.ai_off_plan)
            } catch (e: Exception) {
                aiText = e.message ?: e.javaClass.simpleName
            } finally {
                if (aiJob === me) aiRunning = false
            }
        }
    }

    /** Stops the answer being written and clears it. */
    fun stopAi() {
        aiJob?.cancel()
        aiJob = null
        aiText = null
        aiRunning = false
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
            pushNow()
        }
    }

    /** A note dragged to another place: the list on screen follows the finger; the order is written when the drag ends. */
    fun move(fromId: String, toId: String) {
        val ids = notes.map { it.id }.toMutableList()
        val from = ids.indexOf(fromId); val to = ids.indexOf(toId)
        if (from < 0 || to < 0 || from == to) return
        ids.add(to, ids.removeAt(from))
        val byId = notes.associateBy { it.id }
        // The list on screen is the order; no reload here, a drag moves many times a second and reloads landing
        // out of turn put an older order back under the finger.
        notes = ids.map { byId.getValue(it) }
    }

    /** The drag ended: the moved note's place written (usually that note alone) and sent, so every device shows this order. */
    fun moveDone(id: String) {
        val ids = notes.map { it.id }
        viewModelScope.launch(Guard) {
            withContext(Dispatchers.IO) { db.placeMoved(ids, id) }
            pushNow()
        }
    }

    fun delete(id: String) {
        busy = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.delete(id) }
            reload()
            back()
            busy = false
            pushNow()
        }
    }

    /** The picked notes deleted here at once and sent in one call; the other devices drop them at their next sync. */
    fun deleteMany(ids: Set<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Guard) {
            withContext(Dispatchers.IO) { db.deleteMany(ids) }
            reload()
            pushNow()
        }
    }

    /** A saved or deleted note sent on its own, in one call; the full sync waits for the next open or refresh. */
    private fun pushNow() {
        if (!prefs.signedIn) return
        viewModelScope.launch(Guard) {
            try {
                problem = withContext(Dispatchers.IO) { sync.push() }
                reload()
            } catch (e: SignInRequiredException) {
                onSessionEnded()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message
            }
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

    /** Signing out leaves nothing of the account on the phone: no notes, no index, no search, no session. */
    fun signOut() {
        wipe()
    }

    private fun onSessionEnded() {
        wipe()
    }

    private fun wipe() {
        searchJob?.cancel()
        db.clearAll()
        prefs.clearSession()
        prefs.clearAccountData()
        index.forget()
        notes = emptyList()
        filters = Filters()
        query = ""
        hits = null
        problem = null
        signedIn = false
        screen = Screen.Setup
    }

    /** search.opensolr.com/<index>: the hosted page, opened with the account's address and 2FA code. */
    fun searchPageUrl(): String? = prefs.indexName.takeIf { it.isNotBlank() }?.let { "https://search.opensolr.com/$it" }

    // ---- updates ----

    /** True while a check asked for by hand runs; [upToDate] is set when it found nothing newer. */
    var checkingUpdate by mutableStateOf(false); private set
    var upToDate by mutableStateOf(false); private set

    fun checkForUpdate(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - prefs.lastUpdateCheck < 6 * 3600_000L && update == null) return
        viewModelScope.launch(Guard) {
            prefs.lastUpdateCheck = System.currentTimeMillis()
            if (force) { checkingUpdate = true; upToDate = false; updateError = null }
            val r = UpdateCheck.check()
            r.getOrNull()?.let { update = it }
            if (force) {
                checkingUpdate = false
                if (r.isFailure) updateError = r.exceptionOrNull()?.message else if (r.getOrNull() == null) upToDate = true
            }
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
