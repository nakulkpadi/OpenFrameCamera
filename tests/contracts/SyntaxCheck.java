import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.io.*;
import java.util.*;
/** Uses JDK compiler frontend to parse Java sources without Android SDK class resolution. */
public class SyntaxCheck {
    public static void main(String[] argv) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null);
        Iterable<? extends JavaFileObject> files = fm.getJavaFileObjectsFromStrings(Arrays.asList(argv));
        JavacTask task = (JavacTask)compiler.getTask(null, fm, diagnostics, Arrays.asList("-proc:none"), null, files);
        task.parse();
        int errors=0;
        for (Diagnostic<? extends JavaFileObject> d:diagnostics.getDiagnostics()) {
            if(d.getKind()==Diagnostic.Kind.ERROR) {System.err.println(d);errors++;}
        }
        fm.close();
        if(errors>0) {System.exit(1);}
        System.out.println("PASS: Parsed "+argv.length+" Java files without syntax errors");
    }
}
