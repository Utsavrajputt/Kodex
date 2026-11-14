package com.invictus.kodex.feature.project

import org.eclipse.jgit.api.Git
import java.io.File

/** Starter contents for a new project. */
enum class ProjectTemplate { EMPTY, WEB, PYTHON, NODE }

/** What the New project sheet asked for besides the folder itself. */
data class NewProjectOptions(
    val template: ProjectTemplate = ProjectTemplate.EMPTY,
    val readme: Boolean = true,
    val gitignore: Boolean = true,
    val gitInit: Boolean = true,
    /** Open the project right after it is created (off: it just lands in Recent projects). */
    val open: Boolean = true,
)

/** Writes the starter files of [NewProjectOptions] into a freshly created project folder. Best effort per file. */
internal object ProjectScaffold {

    fun write(dir: File, name: String, options: NewProjectOptions) {
        val title = name.trim()
        when (options.template) {
            ProjectTemplate.EMPTY -> Unit
            ProjectTemplate.WEB -> {
                put(dir, "index.html", webIndex(title))
                put(dir, "style.css", WEB_CSS)
                put(dir, "script.js", "console.log(\"Hello from $title\");\n")
            }
            ProjectTemplate.PYTHON -> put(dir, "main.py", PYTHON_MAIN)
            ProjectTemplate.NODE -> {
                put(dir, "package.json", nodePackage(title))
                put(dir, "index.js", "console.log(\"Hello from $title\");\n")
            }
        }
        if (options.readme) put(dir, "README.md", "# $title\n")
        if (options.gitignore) put(dir, ".gitignore", gitignore(options.template))
        if (options.gitInit) {
            runCatching { Git.init().setDirectory(dir).setInitialBranch("main").call().close() }
        }
    }

    private fun put(dir: File, relative: String, text: String) {
        runCatching { File(dir, relative).writeText(text) }
    }

    private fun gitignore(template: ProjectTemplate): String {
        val common = listOf(".DS_Store", "Thumbs.db", "*.log", ".idea/", ".vscode/")
        val extra = when (template) {
            ProjectTemplate.PYTHON -> listOf("__pycache__/", "*.pyc", ".venv/", "venv/")
            ProjectTemplate.NODE -> listOf("node_modules/", ".env", "dist/")
            else -> emptyList()
        }
        return (extra + common).joinToString("\n", postfix = "\n")
    }

    private fun webIndex(title: String): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>$title</title>
          <link rel="stylesheet" href="style.css">
        </head>
        <body>
          <h1>$title</h1>
          <script src="script.js"></script>
        </body>
        </html>
    """.trimIndent() + "\n"

    private const val WEB_CSS = "body {\n  font-family: system-ui, sans-serif;\n  margin: 2rem;\n}\n"

    private const val PYTHON_MAIN =
        "def main():\n    print(\"Hello, world!\")\n\n\nif __name__ == \"__main__\":\n    main()\n"

    private fun nodePackage(title: String): String {
        val id = title.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.', '_').ifEmpty { "app" }
        return "{\n  \"name\": \"$id\",\n  \"version\": \"1.0.0\",\n  \"main\": \"index.js\",\n  \"scripts\": {\n    \"start\": \"node index.js\"\n  }\n}\n"
    }
}
