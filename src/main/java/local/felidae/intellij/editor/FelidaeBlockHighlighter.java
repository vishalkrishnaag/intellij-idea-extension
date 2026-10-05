package local.felidae.intellij.editor;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import local.felidae.intellij.highlighting.FelidaeTextAttributes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marks only the block under the caret: its opener keyword and its matching
 * {@code end}. A long function closing many nested blocks ends in a stack of
 * {@code end}s; marking all of them is noise, so every other one stays plain.
 *
 * <p>The opener rules match the formatter and the other editor extensions:
 * class, {@code def ... =>}, {@code for}/{@code while}, switch and try own an
 * explicit {@code end}.
 */
public final class FelidaeBlockHighlighter implements EditorFactoryListener {

    private static final Key<List<RangeHighlighter>> HIGHLIGHTERS =
            Key.create("felidae.block.highlighters");
    private static final Key<Disposable> LISTENERS =
            Key.create("felidae.block.listeners");

    private static final Pattern OPENER = Pattern.compile(
            "^\\s*(?:(class|for|while|switch|try)\\b.*"
                    + "|(def)\\s+[A-Za-z_][A-Za-z0-9_:.]*\\s*\\(.*\\)\\s*=>\\s*(?:#.*)?)$");
    private static final Pattern END = Pattern.compile("^\\s*end\\b.*");

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        if (!isFelidae(editor)) return;
        Disposable listeners = Disposer.newDisposable("felidae-block-highlighter");
        editor.putUserData(LISTENERS, listeners);
        editor.getCaretModel().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent caretEvent) {
                update(editor);
            }
        }, listeners);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent documentEvent) {
                update(editor);
            }
        }, listeners);
        update(editor);
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        clear(editor);
        Disposable listeners = editor.getUserData(LISTENERS);
        if (listeners != null) {
            editor.putUserData(LISTENERS, null);
            Disposer.dispose(listeners);
        }
    }

    private static boolean isFelidae(Editor editor) {
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        return file != null && "fx".equals(file.getExtension());
    }

    private static void clear(Editor editor) {
        List<RangeHighlighter> existing = editor.getUserData(HIGHLIGHTERS);
        if (existing == null) return;
        for (RangeHighlighter highlighter : existing) {
            editor.getMarkupModel().removeHighlighter(highlighter);
        }
        editor.putUserData(HIGHLIGHTERS, null);
    }

    private static void update(Editor editor) {
        clear(editor);
        Document document = editor.getDocument();
        int lineCount = document.getLineCount();
        if (lineCount == 0) return;
        String[] lines = new String[lineCount];
        for (int line = 0; line < lineCount; line++) {
            lines[line] = document.getText(
                    new com.intellij.openapi.util.TextRange(
                            document.getLineStartOffset(line), document.getLineEndOffset(line)));
        }
        int row = document.getLineNumber(editor.getCaretModel().getOffset());
        int[] block = enclosingBlock(lines, row);
        if (block == null) return;

        List<RangeHighlighter> created = new ArrayList<>();
        for (int line : block) {
            Matcher word = Pattern.compile("\\S+").matcher(lines[line]);
            if (!word.find()) continue;
            int start = document.getLineStartOffset(line) + word.start();
            int end = document.getLineStartOffset(line) + word.start() + keywordLength(lines[line], word);
            created.add(editor.getMarkupModel().addRangeHighlighter(
                    FelidaeTextAttributes.BLOCK_MATCH,
                    start,
                    end,
                    HighlighterLayer.SELECTION - 1,
                    HighlighterTargetArea.EXACT_RANGE));
        }
        editor.putUserData(HIGHLIGHTERS, created);
    }

    /** Only the leading keyword is marked, not the rest of an opener line. */
    private static int keywordLength(String line, Matcher firstWord) {
        int length = 0;
        int index = firstWord.start();
        while (index < line.length() && Character.isLetter(line.charAt(index))) {
            index++;
            length++;
        }
        return Math.max(length, 1);
    }

    /** Returns {openerLine, endLine} of the innermost block around ROW, or null. */
    static int[] enclosingBlock(String[] lines, int row) {
        int open = -1;
        if (OPENER.matcher(lines[row]).matches()) {
            open = row;
        } else {
            // An 'end' line is not counted: its own opener is the first
            // unmatched opener above it.
            int depth = 0;
            for (int line = row - 1; line >= 0; line--) {
                if (END.matcher(lines[line]).matches()) {
                    depth++;
                } else if (OPENER.matcher(lines[line]).matches()) {
                    if (depth == 0) {
                        open = line;
                        break;
                    }
                    depth--;
                }
            }
        }
        if (open < 0) return null;
        int depth = 0;
        for (int line = open; line < lines.length; line++) {
            if (OPENER.matcher(lines[line]).matches()) {
                depth++;
            } else if (END.matcher(lines[line]).matches()) {
                depth--;
                if (depth == 0) return new int[] {open, line};
            }
        }
        return null;
    }
}
