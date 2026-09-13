package local.felidae.intellij.settings;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Settings > Tools > Felidae: lets a user point the plugin at
 * felidae/celidae executables directly, instead of relying
 * only on environment variables or auto-detected build/bin locations
 * (FelidaeExecutableResolver). Mirrors the VS Code extension's
 * felidae.interpreterPath / celidaePath settings.
 */
public final class FelidaeConfigurable implements Configurable {

    private final Project project;

    private TextFieldWithBrowseButton interpreterField;
    private TextFieldWithBrowseButton celidaeField;
    private JPanel panel;

    public FelidaeConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "Felidae";
    }

    @Override
    public @Nullable JComponent createComponent() {
        interpreterField = browsableField("Select Felidae Interpreter");
        celidaeField = browsableField("Select Celidae Visualizer");

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Felidae interpreter (felidae.exe):"), interpreterField, 1, false)
                .addLabeledComponent(new JBLabel("Celidae visualizer (celidae.exe):"), celidaeField, 1, false)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        return panel;
    }

    private @NotNull TextFieldWithBrowseButton browsableField(@NotNull String title) {
        TextFieldWithBrowseButton field = new TextFieldWithBrowseButton();
        field.addBrowseFolderListener(
                project,
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle(title)
        );
        return field;
    }

    @Override
    public boolean isModified() {
        FelidaeSettingsState state = FelidaeSettingsState.getInstance(project);
        return !interpreterField.getText().equals(state.getInterpreterPath()) ||
                !celidaeField.getText().equals(state.getCelidaePath());
    }

    @Override
    public void apply() {
        FelidaeSettingsState state = FelidaeSettingsState.getInstance(project);
        state.setInterpreterPath(interpreterField.getText().trim());
        state.setCelidaePath(celidaeField.getText().trim());
    }

    @Override
    public void reset() {
        FelidaeSettingsState state = FelidaeSettingsState.getInstance(project);
        interpreterField.setText(state.getInterpreterPath());
        celidaeField.setText(state.getCelidaePath());
    }
}
