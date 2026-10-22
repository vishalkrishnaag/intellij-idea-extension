package local.felidae.intellij;

import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Quick Documentation (Ctrl+Q / hover) for Felidae stdlib calls, backed by
 * the same content the VS Code extension shows (FelidaeBuiltinDocs, bundled
 * from the shared repo-root docs/builtin-docs.json).
 *
 * There is no PSI grammar for Felidae, so .fx files have no meaningful
 * per-token PsiElement to resolve a documentation target from. Instead this
 * resolves the identifier under the caret directly from the editor text
 * (mirroring FelidaeExternalAnnotator's raw-offset approach) and stashes the
 * resolved doc key as user data on the context element so generateDoc can
 * read it back.
 */
public final class FelidaeDocumentationProvider extends AbstractDocumentationProvider {

    private static final Key<String> DOC_KEY = Key.create("felidae.documentation.key");

    @Override
    public @Nullable PsiElement getCustomDocumentationElement(
            @NotNull Editor editor,
            @NotNull PsiFile file,
            @Nullable PsiElement contextElement,
            int targetOffset
    ) {
        if (contextElement == null) {
            return null;
        }
        String key = resolveKey(editor.getDocument().getCharsSequence(), targetOffset);
        if (key == null) {
            return null;
        }
        contextElement.putUserData(DOC_KEY, key);
        return contextElement;
    }

    @Override
    public @Nullable String generateDoc(@NotNull PsiElement element, @Nullable PsiElement originalElement) {
        String key = element.getUserData(DOC_KEY);
        if (key == null) {
            return null;
        }

        FelidaeBuiltinDocs.Entry entry = FelidaeBuiltinDocs.get(key);
        if (entry != null) {
            return render(entry.heading(), signature(entry.heading(), toParams(entry)), entry.description(), toParams(entry), entry.example());
        }

        // Not a builtin: document the user's own declarations too, so hovering
        // a fact or method shows the parameters it expects.
        PsiFile file = element.getContainingFile();
        if (file == null) {
            return null;
        }
        FelidaeCallResolver.ResolvedCall resolved =
                FelidaeCallResolver.resolve(file.getText(), key);
        if (resolved == null) {
            return null;
        }
        return render(resolved.label(), signature(resolved.label(), resolved.params()), resolved.detail(), resolved.params(), null);
    }

    /**
     * Quick Documentation laid out the way Java's and Kotlin's own providers do: a bold signature as the
     * {@code definition}, the prose as {@code content}, then the {@code sections} table (DocumentationMarkup,
     * the same helper {@code JavaDocInfoGenerator}/{@code KotlinQuickDocumentationProvider} build on) for the
     * parameter list and, for a builtin, a runnable example.
     */
    private static @NotNull String render(
            @NotNull String heading,
            @NotNull String signature,
            @NotNull String description,
            @NotNull List<FelidaeCallResolver.Param> params,
            @Nullable String example
    ) {
        StringBuilder html = new StringBuilder();
        html.append(DocumentationMarkup.DEFINITION_START).append("<b>").append(escape(signature)).append("</b>")
                .append(DocumentationMarkup.DEFINITION_END);
        html.append(DocumentationMarkup.CONTENT_START).append(escape(description)).append(DocumentationMarkup.CONTENT_END);
        if (!params.isEmpty() || example != null) {
            html.append(DocumentationMarkup.SECTIONS_START);
            if (!params.isEmpty()) appendSection(html, "Parameters:", parameterList(params));
            if (example != null) appendSection(html, "Example:", "<pre>" + escape(example) + "</pre>");
            html.append(DocumentationMarkup.SECTIONS_END);
        }
        return html.toString();
    }

    private static void appendSection(@NotNull StringBuilder html, @NotNull String label, @NotNull String body) {
        html.append(DocumentationMarkup.SECTION_HEADER_START).append(label).append(DocumentationMarkup.SECTION_SEPARATOR)
                .append(body).append(DocumentationMarkup.SECTION_END);
    }

    private static @NotNull String signature(@NotNull String heading, @NotNull List<FelidaeCallResolver.Param> params) {
        StringBuilder signature = new StringBuilder(heading).append("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) signature.append(", ");
            signature.append(params.get(i).label());
        }
        return signature.append(")").toString();
    }

    private static @NotNull List<FelidaeCallResolver.Param> toParams(
            @NotNull FelidaeBuiltinDocs.Entry entry
    ) {
        List<FelidaeCallResolver.Param> params = new ArrayList<>(entry.params().size());
        for (FelidaeBuiltinDocs.Param param : entry.params()) {
            params.add(new FelidaeCallResolver.Param(param.name(), param.type()));
        }
        return params;
    }

    /** One line per named argument, as the body of the "Parameters:" section. */
    private static @NotNull String parameterList(@NotNull List<FelidaeCallResolver.Param> params) {
        StringBuilder html = new StringBuilder();
        for (FelidaeCallResolver.Param param : params) {
            html.append("<p><code>").append(escape(param.name())).append("</code>");
            if (!param.type().isEmpty()) {
                html.append(" &ndash; ").append(escape(param.type()));
            }
            html.append("</p>");
        }
        return html.toString();
    }

    private static @Nullable String resolveKey(@NotNull CharSequence text, int offset) {
        int start = Math.max(0, Math.min(offset, text.length()));
        int end = start;
        while (start > 0 && isNameChar(text.charAt(start - 1))) start--;
        while (end < text.length() && isNameChar(text.charAt(end))) end++;
        if (start >= end) {
            return null;
        }
        String raw = text.subSequence(start, end).toString().replaceAll("^[.:]+|[.:]+$", "");
        if (raw.isEmpty()) {
            return null;
        }
        String normalized = raw.replace('.', ':');
        if (FelidaeBuiltinDocs.contains(normalized)) {
            return normalized;
        }
        if (FelidaeBuiltinDocs.contains(raw)) {
            return raw;
        }
        // Fall through to user-defined declarations, resolved in generateDoc
        // against the file's own text.
        return raw;
    }

    private static boolean isNameChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == ':' || ch == '.';
    }

    private static @NotNull String escape(@NotNull String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
