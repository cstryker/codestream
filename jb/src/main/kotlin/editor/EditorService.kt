package com.codestream.editor

import com.codestream.agentService
import com.codestream.codeStream
import com.codestream.extensions.displayPath
import com.codestream.extensions.file
import com.codestream.extensions.getOffset
import com.codestream.extensions.highlightTextAttributes
import com.codestream.extensions.isRangeVisible
import com.codestream.extensions.lspPosition
import com.codestream.extensions.margins
import com.codestream.extensions.selections
import com.codestream.extensions.textDocumentIdentifier
import com.codestream.extensions.uri
import com.codestream.extensions.visibleRanges
import com.codestream.protocols.agent.DocumentMarker
import com.codestream.protocols.agent.DocumentMarkersParams
import com.codestream.protocols.agent.Marker
import com.codestream.protocols.agent.TextDocument
import com.codestream.protocols.webview.EditorContext
import com.codestream.protocols.webview.EditorInformation
import com.codestream.protocols.webview.EditorMetrics
import com.codestream.protocols.webview.EditorNotifications
import com.codestream.protocols.webview.EditorSelection
import com.codestream.protocols.webview.WebViewContext
import com.codestream.review.ReviewDiffFileSystem
import com.codestream.review.ReviewDiffSide
import com.codestream.review.ReviewDiffVirtualFile
import com.codestream.sessionService
import com.codestream.settings.ApplicationSettingsService
import com.codestream.settingsService
import com.codestream.system.sanitizeURI
import com.codestream.webViewService
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import java.io.File
import java.net.URI

class EditorService(val project: Project) {

    init {
        project.sessionService?.onUserLoggedInChanged { updateMarkers() }
        project.settingsService?.onWebViewContextChanged(this::onWebViewContextChanged)
        project.codeStream?.onIsVisibleChanged(this::onCodeStreamIsVisibleChanged)
        project.agentService?.onRestart(this::refresh)
    }

    private val logger = Logger.getInstance(EditorService::class.java)
    private val appSettings = ServiceManager.getService(ApplicationSettingsService::class.java)

    private val managedDocuments = mutableMapOf<Document, DocumentVersion>()
    private val managedEditors = mutableSetOf<Editor>()
    private val rangeHighlighters = mutableMapOf<Editor, MutableSet<RangeHighlighter>>()
    private val markerHighlighters = mutableMapOf<Editor, List<RangeHighlighter>>()
    private val documentMarkers = mutableMapOf<Document, List<DocumentMarker>>()
    private var spatialViewActive = project.settingsService?.webViewContext?.spatialViewVisible ?: false
    private var codeStreamVisible = project.codeStream?.isVisible ?: false

    fun add(editor: Editor) {
        val agentService = project.agentService ?: return
        managedEditors.add(editor)
        rangeHighlighters[editor] = mutableSetOf()
        editor.selectionModel.addSelectionListener(SelectionListenerImpl(project))
        editor.scrollingModel.addVisibleAreaListener(VisibleAreaListenerImpl(project))

        val document = editor.document
        agentService.onDidStart {
            synchronized(managedDocuments) {
                if (document.uri?.startsWith("file://") != true) return@synchronized
                if (!managedDocuments.contains(document)) {
                    managedDocuments[document] = DocumentVersion()
                    agentService.agent.textDocumentService.didOpen(
                        DidOpenTextDocumentParams(document.textDocumentItem)
                    )
                    document.addDocumentListener(DocumentSynchronizer())
                }
            }
        }
    }

    fun remove(editor: Editor) {
        val agentService = project.agentService ?: return
        managedEditors.remove(editor)
        rangeHighlighters.remove(editor)

        val document = editor.document
        agentService.onDidStart {
            synchronized(managedDocuments) {
                if (document.uri?.startsWith("file://") != true) return@synchronized
                val editors = EditorFactory.getInstance().getEditors(document, project)
                if (editors.none { it != editor }) {
                    managedDocuments.remove(document)
                    document.textDocumentIdentifier?.let {
                        agentService.agent.textDocumentService.didClose(
                            DidCloseTextDocumentParams(it)
                        )
                    }
                }
            }
        }
    }

    fun save(document: Document) {
        val agentService = project.agentService ?: return

        synchronized(managedDocuments) {
            if (managedDocuments.contains(document)) {
                agentService.agent.textDocumentService.didSave(
                    DidSaveTextDocumentParams(document.textDocumentIdentifier)
                )
            }
        }
    }

    private fun refresh() {
        synchronized(managedDocuments) {
            val textDocumentService = project.agentService?.agent?.textDocumentService ?: return
            managedDocuments.keys.forEach {
                textDocumentService.didOpen(
                    DidOpenTextDocumentParams(it.textDocumentItem)
                )
                managedDocuments[it] = DocumentVersion()
            }
        }
    }

    private fun onWebViewContextChanged(context: WebViewContext?) {
        context?.let {
            if (spatialViewActive != it.spatialViewVisible) {
                spatialViewActive = it.spatialViewVisible
                updateMarkers()
            }
        }
    }

    private fun onCodeStreamIsVisibleChanged(isVisible: Boolean) {
        if (isVisible == codeStreamVisible) return

        codeStreamVisible = isVisible
        updateMarkers()

        GlobalScope.launch {
            delay(250L)
            ApplicationManager.getApplication().invokeLater {
                val editor = activeEditor
                if (isVisible && editor != null && editor.document.uri != null && !editor.isDisposed)
                    project.webViewService?.postNotification(
                        EditorNotifications.DidChangeVisibleRanges(
                            editor.document.uri,
                            editor.selections,
                            editor.visibleRanges,
                            editor.document.lineCount
                        )
                    )
            }
        }
    }

    private fun updateMarkers() {
        for ((document, _) in managedDocuments) {
            updateMarkers(document)
        }
    }

    fun updateMarkers(uri: String) {
        val document = managedDocuments.keys.find { it.uri == uri }
        document?.let {
            updateMarkers(it)
        }
    }

    fun updatePullRequestDiffMarkers() {
        managedEditors
            .filter {
                val reviewFile = it.document.file as? ReviewDiffVirtualFile
                reviewFile?.side == ReviewDiffSide.RIGHT
            }
            .forEach {
                GlobalScope.launch {
                    val markers = getDocumentMarkers(it.document)
                    it.renderMarkers(markers)
                }
            }
    }

    fun updateMarkers(document: Document) = ApplicationManager.getApplication().invokeLater {
        val editors = EditorFactory.getInstance().getEditors(document, project)
        val visibleEditors = editors
            .filter { !it.scrollingModel.visibleArea.isEmpty }
            .filter {
                val reviewFile = it.document.file as? ReviewDiffVirtualFile ?: return@filter true
                reviewFile.side == ReviewDiffSide.RIGHT
            }
        if (visibleEditors.isEmpty()) return@invokeLater

        GlobalScope.launch {
            val markers = getDocumentMarkers(document)
            visibleEditors.forEach { it.renderMarkers(markers) }
        }
    }

    private suspend fun getDocumentMarkers(document: Document): List<DocumentMarker> {
        val agent = project.agentService ?: return emptyList()
        val session = project.sessionService ?: return emptyList()
        val uri = document.uri

        val markers = if (uri == null || session.userLoggedIn == null || !appSettings.showMarkers) {
            emptyList()
        } else {
            val result = agent.documentMarkers(DocumentMarkersParams(TextDocument(uri)))
            result.markers
        }

        documentMarkers[document] = markers

        return markers.filter {
            it.codemark == null || (it.codemark.status != "closed" && it.codemark.pinned == true)
        }
    }

    private val showGutterIcons: Boolean
        get() {
            return !codeStreamVisible || !spatialViewActive || !appSettings.autoHideMarkers
        }

    inner class DocumentSynchronizer : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            val agentService = project.agentService ?: return
            if (!managedDocuments.contains(event.document)) return
            when (agentService.syncKind) {
                TextDocumentSyncKind.Incremental -> {
                    val changesParams = DidChangeTextDocumentParams(
                        event.document.versionedTextDocumentIdentifier,
                        listOf(event.textDocumentContentChangeEvent)

                    )
                    agentService.agent.textDocumentService.didChange(changesParams)
                }
                TextDocumentSyncKind.Full -> {
                    val change = TextDocumentContentChangeEvent(
                        null, null, event.document.text
                    )
                    val changesParams = DidChangeTextDocumentParams(
                        event.document.versionedTextDocumentIdentifier,
                        listOf(change)

                    )
                    agentService.agent.textDocumentService.didChange(changesParams)
                }
                else -> throw IllegalArgumentException("Unsupported document synchronization kind")
            }
        }
    }

    inner class DocumentVersion {
        private var value = 1
        val nextValue: Int
            get() = value++
    }

    private val Document.nextVersion: Int
        get() {
            val version = managedDocuments[this]
            return version?.nextValue ?: throw IllegalStateException("Cannot retrieve version of non-managed document")
        }

    private val Document.textDocumentItem: TextDocumentItem?
        get() = TextDocumentItem(uri, "", nextVersion, text)

    private val Document.versionedTextDocumentIdentifier: VersionedTextDocumentIdentifier
        get() {
            return VersionedTextDocumentIdentifier(uri, nextVersion)
        }

    private val DocumentEvent.textDocumentContentChangeEvent: TextDocumentContentChangeEvent
        get() {
            // if text was deleted/replaced, calculate the end lspPosition of inserted/deleted text
            val position = this.lspPosition
            val startPosition = Position(position.line, position.character)
            val endPosition = if (oldFragment.isNotEmpty()) {
                val line = position.line + StringUtil.countNewLines(oldFragment)
                val oldLines = oldFragment.toString().split('\n')
                val oldTextLength = if (oldLines.isEmpty()) 0 else oldLines.last().length
                val column = if (oldLines.size == 1) position.character + oldTextLength else oldTextLength
                Position(line, column)
            } else {
                startPosition // if insert or no text change, the end lspPosition is the same
            }
            val range = Range(startPosition, endPosition)
            return TextDocumentContentChangeEvent(
                range, newLength, newFragment.toString()
            )
        }

    private fun Editor.renderMarkers(markers: List<DocumentMarker>) = ApplicationManager.getApplication().invokeLater {
        if (isDisposed) return@invokeLater

        markerHighlighters[this]?.let { highlighters ->
            highlighters.forEach { highlighter ->
                markupModel.removeHighlighter(highlighter)
            }
        }

        markerHighlighters[this] = markers.filter { marker ->
            marker.range.start.line >= 0
        }.map { marker ->
            val start = getOffset(marker.range.start)
            val end = getOffset(marker.range.end)

            markupModel.addRangeHighlighter(
                Math.min(start, end),
                Math.max(start, end),
                HighlighterLayer.LAST,
                null,
                HighlighterTargetArea.EXACT_RANGE
            ).also {
                if (showGutterIcons && (marker.codemark != null || marker.externalContent != null)) {
                    it.gutterIconRenderer = GutterIconRendererImpl(this, marker)
                }
                it.isThinErrorStripeMark = true
                it.errorStripeMarkColor = marker.codemark?.color() ?: blue
                it.errorStripeTooltip = marker.summary
            }
        }
    }

    private var _activeEditor: Editor? = null
    var activeEditor: Editor?
        get() = _activeEditor
        set(editor) {
            val validEditor = if (editor?.document?.uri != null) {
                editor
            } else {
                null
            }

            _activeEditor = validEditor

            val editorInfo = validEditor?.run {
                EditorInformation(
                    displayPath,
                    document.uri,
                    EditorMetrics(
                        colorsScheme.editorFontSize,
                        lineHeight,
                        margins
                    ),
                    selections,
                    visibleRanges,
                    document.lineCount
                )
            }
            val notification = EditorNotifications.DidChangeActive(editorInfo)
            project.webViewService?.postNotification(notification)
        }

    suspend fun getEditorContext(): EditorContext? {
        val future = CompletableDeferred<EditorContext?>()
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                future.complete(null)
                return@invokeLater
            }
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val context = if (editor?.document?.uri != null) {
                EditorContext(
                    null,
                    editor.displayPath,
                    null,
                    editor.visibleRanges,
                    editor.document.uri,
                    editor.selections,
                    EditorMetrics(
                        editor.colorsScheme.editorFontSize,
                        editor.lineHeight,
                        editor.margins
                    )
                )
            } else {
                EditorContext()
            }
            future.complete(context)
        }
        return future.await()
    }

    private val _highlightTextAttributes = mutableMapOf<Editor, TextAttributes>()
    private fun getHighlightTextAttributes(editor: Editor): TextAttributes = _highlightTextAttributes.getOrPut(editor) {
        editor.highlightTextAttributes
    }

    fun toggleRangeHighlight(range: Range?, highlight: Boolean) = ApplicationManager.getApplication().invokeLater {
        val editor = activeEditor ?: return@invokeLater
        val highlighters = rangeHighlighters[editor]

        if (highlighters == null) {
            logger.warn("Highlighters not initialized for editor $editor")
            return@invokeLater
        }

        if (!highlight || range == null) {
            synchronized(highlighters) {
                for (highlighter in highlighters) {
                    editor.markupModel.removeHighlighter(highlighter)
                    highlighter.dispose()
                }
                highlighters.clear()
            }
            return@invokeLater
        }

        synchronized(highlighters) {
            if (range.start.line >= editor.document.lineCount) {
                return@invokeLater
            }

            val highlighter =
                if (range.start.line == range.end.line && range.start.character == 0 && range.end.character > 1000) {
                    editor.markupModel.addLineHighlighter(
                        range.start.line, HighlighterLayer.LAST, getHighlightTextAttributes(editor)
                    )
                } else {
                    val start = editor.getOffset(range.start)
                    val end = editor.getOffset(range.end)

                    editor.markupModel.addRangeHighlighter(
                        Math.min(start, end),
                        Math.max(start, end),
                        HighlighterLayer.LAST,
                        getHighlightTextAttributes(editor),
                        HighlighterTargetArea.EXACT_RANGE
                    )
                }
            highlighters.add(highlighter)
        }
    }

    suspend fun reveal(uri: String, range: Range?, atTop: Boolean? = null): Boolean {
        val future = CompletableDeferred<Boolean>()
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            editor?.let {
                if (it.document.uri == uri && (range == null || it.isRangeVisible(range))) {
                    future.complete(true)
                    return@invokeLater
                }
            }

            val line = range?.start?.line ?: 0
            val virtualFile = LocalFileSystem.getInstance().findFileByIoFile(File(URI(uri)))
            if (virtualFile == null) {
                future.complete(false)
                return@invokeLater
            }

            if (editor?.document?.uri == uri && range != null && atTop == true) {
                val logicalPosition = LogicalPosition(range.start.line, range.start.character)
                val point = editor.logicalPositionToXY(logicalPosition)
                editor.scrollingModel.scrollVertically(point.y)
            } else {
                val editorManager = FileEditorManager.getInstance(project)
                editorManager.openTextEditor(OpenFileDescriptor(project, virtualFile, line, 0), true)
            }

            future.complete(true)
        }
        return future.await()
    }

    suspend fun select(uriString: String, selection: EditorSelection, preserveFocus: Boolean): Boolean {
        val future = CompletableDeferred<Boolean>()
        ApplicationManager.getApplication().invokeLater {
            var editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor?.document?.uri != uriString || !editor.isRangeVisible(selection)) {
                val uri = URI(uriString)
                val virtualFile = if (uri.scheme == ReviewDiffFileSystem.protocol) {
                    FileEditorManager.getInstance(project).openFiles.find { it.uri == uriString }
                } else {
                    LocalFileSystem.getInstance().findFileByIoFile(File(uri))
                }

                if (virtualFile == null) {
                    future.complete(false)
                    return@invokeLater
                }

                val editorManager = FileEditorManager.getInstance(project)
                val line = selection.start.line
                editor = editorManager.openTextEditor(OpenFileDescriptor(project, virtualFile, line, 0), true)
            }

            if (editor == null) {
                future.complete(false)
                return@invokeLater
            }

            editor.apply {
                val start = getOffset(selection.start)
                val end = getOffset(selection.end)
                val caret = getOffset(selection.cursor)
                selectionModel.setSelection(start, end)
                caretModel.moveToOffset(caret)
                if (!preserveFocus) component.grabFocus()
            }

            future.complete(true)
        }
        return future.await()
    }

    fun scroll(uri: String, position: Position, atTop: Boolean) = ApplicationManager.getApplication().invokeLater {
        var editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater
        if (editor.document.uri != sanitizeURI(uri)) {
            return@invokeLater
        }

        val logicalPosition = LogicalPosition(position.line, position.character)
        val point = editor.logicalPositionToXY(logicalPosition)

        editor.scrollingModel.scrollVertically(point.y)
    }

    fun compareMarker(marker: Marker) = ApplicationManager.getApplication().invokeLater {
        var editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater
        val documentMarkers = documentMarkers[editor.document] ?: return@invokeLater
        val documentMarker = documentMarkers.find { it.id == marker.id } ?: return@invokeLater

        val project = editor.project

        val start = editor.getOffset(documentMarker.range.start)
        val end = editor.getOffset(documentMarker.range.end)
        val text = editor.document.text
        val pre = text.substring(0, start)
        val pos = if (end < text.length) text.substring(end + 1, text.length) else ""
        val codemarkContent = pre + marker.code + pos

        val fileType = editor.document.file?.fileType
        val content1 = DiffContentFactory.getInstance().create(project, editor.document, fileType)
        val content2 = DiffContentFactory.getInstance().create(project, codemarkContent, fileType)
        val request = SimpleDiffRequest("Codemark", content1, content2, "Your version", "Codemark version")
        request.putUserData(DiffUserDataKeys.GO_TO_SOURCE_DISABLE, true)
        DiffManager.getInstance().showDiff(project, request)
    }

    fun applyMarker(marker: Marker) = ApplicationManager.getApplication().invokeLater {
        var editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater
        val documentMarkers = documentMarkers[editor.document] ?: return@invokeLater
        val documentMarker = documentMarkers.find { it.id == marker.id } ?: return@invokeLater

        with(editor) {
            val start = getOffset(documentMarker.range.start)
            val end = getOffset(documentMarker.range.end)
            WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(start, end, marker.code)
            }
        }
    }

    fun insertText(marker: Marker, text: String) = ApplicationManager.getApplication().invokeLater {
        var editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater
        val documentMarkers = documentMarkers[editor.document] ?: return@invokeLater
        val documentMarker = documentMarkers.find { it.id == marker.id } ?: return@invokeLater

        with(editor) {
            val start = getOffset(documentMarker.range.start)
            WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(start, start, text)
            }
        }
    }

    // var count = 0
    // fun toggleRangeHighlight2(range: Range, highlight: Boolean) = ApplicationManager.getApplication().invokeLater {
    //     val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater
    //     val highlighters =
    //         rangeHighlighters[editor] ?: throw IllegalStateException("No highlighters found for editor $editor")
    //
    //     if (!highlight) {
    //         synchronized(highlighters) {
    //             println("remove ${range.arrayString}")
    //             println(--count)
    //             val highlighter = highlighters[range]
    //                 ?: throw IllegalStateException("No highlighter found for range $range")
    //             editor.markupModel.removeHighlighter(highlighter)
    //             highlighters.remove(range)
    //         }
    //         return@invokeLater
    //     }
    //
    //     val highlightTextAttributes = TextAttributes(
    //         null, //editor.colorsScheme.getColor(EditorColors.CARET_ROW_COLOR),
    //         editor.colorsScheme.getColor(EditorColors.CARET_ROW_COLOR),
    //         null, //editor.colorsScheme.getColor(EditorColors.CARET_ROW_COLOR),
    //         null, //EffectType.BOXED,
    //         Font.PLAIN
    //     )
    //
    //     synchronized(highlighters) {
    //         println("add ${range.arrayString}")
    //         println(++count)
    //         val highlighter = if (range.start == range.end && range.start.character == 0) {
    //             editor.markupModel.addLineHighlighter(
    //                 range.start.line, HighlighterLayer.LAST, highlightTextAttributes
    //             )
    //         } else {
    //             editor.markupModel.addRangeHighlighter(
    //                 editor.getOffset(range.start),
    //                 editor.getOffset(range.end),
    //                 HighlighterLayer.LAST,
    //                 highlightTextAttributes,
    //                 HighlighterTargetArea.EXACT_RANGE
    //             )
    //         }
    //         highlighters[range] = highlighter
    //     }
    // }
}
