package unified.llm

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import java.awt.Color
import javax.swing.UIManager

/**
 * Handles extraction of IDE theme colors and generation of CSS variables.
 */
object ThemeColors {

    fun generateCssBlock(): String {
        val sb = StringBuilder()
        sb.append(":root {\n")

        // 1. Core Platform Colors
        sb.append("  --ide-bg: ${toCssColor(UIUtil.getPanelBackground())};\n")
        sb.append("  --ide-fg: ${toCssColor(UIUtil.getLabelForeground())};\n")
        sb.append("  --ide-border: ${toCssColor(UIUtil.getBoundsColor())};\n")

        // 2. Editor Colors
        val scheme = EditorColorsManager.getInstance().globalScheme
        sb.append("  --ide-editor-bg: ${toCssColor(scheme.defaultBackground)};\n")
        sb.append("  --ide-editor-fg: ${toCssColor(scheme.defaultForeground)};\n")

        // 3. Comprehensive UI Keys for Semantic Mapping
        val uiKeys = listOf(
            // General
            "Panel.background", "Panel.foreground",
            "Label.background", "Label.foreground", "Label.disabledForeground", "Label.infoForeground",
            "Borders.color", "Borders.ContrastBorderColor", "Separator.separatorColor",
            
            // Primary / Default Buttons
            "Button.default.startBackground", "Button.default.endBackground", "Button.default.foreground", 
            "Button.default.borderColor", "Button.default.focusColor", "Button.default.focusedBorderColor",
            
            // Secondary Buttons
            "Button.startBackground", "Button.endBackground", "Button.foreground", "Button.borderColor",
            "Button.disabledText", "Button.disabledBorderColor", "Button.focusedBorderColor",
            
            // Inputs & Controls
            "TextField.background", "TextField.foreground", "TextField.borderColor", "TextField.caretForeground",
            "TextField.selectionBackground", "TextField.selectionForeground", "TextField.focusedBorderColor",
            "ComboBox.background", "ComboBox.foreground", "ComboBox.modifiedItemForeground",
            "CheckBox.background", "CheckBox.foreground", "RadioButton.background",
            
            // Lists, Trees, Tables (Selection & States)
            "List.background", "List.foreground", "List.selectionBackground", "List.selectionForeground", 
            "List.selectionInactiveBackground", "List.hoverBackground",
            "Tree.background", "Tree.selectionBackground", "Tree.selectionForeground", "Tree.hoverBackground",
            "Table.background", "Table.gridColor", "Table.selectionBackground",
            
            // Toolbars & Sidebars
            "ToolWindow.header.background", "ToolWindow.header.closeButtonHotBackground",
            "Toolbar.background", "Toolbar.hoverBackground",
            
            // Status & Validation
            "Validation.errorNoFill", "Validation.errorFill", "Validation.warningNoFill", "Validation.infoNoFill",
            "Notification.background", "Notification.foreground", "Notification.errorBackground", "Notification.errorForeground",
            
            // Other Components
            "TabbedPane.background", "TabbedPane.selectedBackground", "TabbedPane.focusColor",
            "ScrollBar.trackColor", "ScrollBar.thumbColor", "ToolTip.background", "ToolTip.foreground"
        )

        for (key in uiKeys) {
            // Fallback: fully transparent if key unknown (avoids invisible UI; theme may still override)
            val color = UIManager.getColor(key) ?: JBColor.namedColor(key, Color(0, 0, 0, 0))
            val cssVarName = "--ide-" + key.replace(".", "-")
            sb.append("  $cssVarName: ${toCssColor(color)};\n")
        }

        // 4. Syntax Highlighting
        val syntaxMap = mapOf(
            "keyword" to DefaultLanguageHighlighterColors.KEYWORD,
            "string" to DefaultLanguageHighlighterColors.STRING,
            "number" to DefaultLanguageHighlighterColors.NUMBER,
            "comment" to DefaultLanguageHighlighterColors.LINE_COMMENT,
            "function" to DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
            "class" to DefaultLanguageHighlighterColors.CLASS_NAME,
            "tag" to DefaultLanguageHighlighterColors.MARKUP_TAG,
            "attr" to DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE
        )

        for ((name, key) in syntaxMap) {
            val attrs = scheme.getAttributes(key)
            val color = attrs?.foregroundColor
            if (color != null) {
                sb.append("  --ide-syntax-$name: ${toCssColor(color)};\n")
            }
        }

        sb.append("}\n")
        
        val font = UIUtil.getLabelFont()
        sb.append("html, body { height: 100%; margin: 0; padding: 0; overflow: hidden; }\n")
        sb.append("body { background-color: var(--ide-bg); color: var(--ide-fg); font-family: '${font.family}', sans-serif; font-size: ${font.size}px; line-height: 1.5; -webkit-font-smoothing: antialiased; }\n")

        return sb.toString()
    }

    private fun toCssColor(color: Color): String {
        val alpha = color.alpha / 255.0
        return if (alpha >= 1.0) {
            "rgb(${color.red}, ${color.green}, ${color.blue})"
        } else {
            "rgba(${color.red}, ${color.green}, ${color.blue}, ${String.format("%.2f", alpha)})"
        }
    }
}
