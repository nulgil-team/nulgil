import com.sun.source.util.JavacTask;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/** Parse only. Deliberately does NOT resolve Android/SDK types or generate an APK. */
public final class AndroidSyntaxCheck {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full JDK is required");
        List<Path> sources;
        try (var files = Files.walk(Path.of(args[0]))) {
            sources = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        if (sources.isEmpty()) throw new IllegalStateException("No native sources");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                List.of("-proc:none", "--release", "17"), null, manager.getJavaFileObjectsFromPaths(sources));
            int parsed = 0;
            for (var unit : task.parse()) parsed++;
            for (var diagnostic : diagnostics.getDiagnostics())
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    throw new AssertionError(diagnostic.toString());
            if (parsed != sources.size()) throw new AssertionError("Incomplete source parse");
            System.out.println("AndroidSyntaxCheck PASS " + parsed + " files; parse only, Android type/build/runtime NOT verified");
        }
    }
}
