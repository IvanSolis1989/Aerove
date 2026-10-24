import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.DiagnosticsLevel;
import com.android.tools.r8.diagnostic.DefinitionContext;
import com.android.tools.r8.diagnostic.MissingDefinitionInfo;
import com.android.tools.r8.diagnostic.MissingDefinitionsDiagnostic;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.tracereferences.TraceReferences;
import com.android.tools.r8.tracereferences.TraceReferencesCommand;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Full typed R8 contexts keep project/app ABI errors fatal; third-party compatibility stays deferred. */
public final class AndroidTestAppAbiTrace implements DiagnosticsHandler {
  private final Set<String> projects;
  private final Set<String> dependencies;
  private final Set<String> targets;
  private final List<String> productPrefixes;
  private final List<Map<String, Object>> definitions = new ArrayList<>();
  private int fatalCount;
  private int deferredCount;

  private AndroidTestAppAbiTrace(String[] args) throws Exception {
    projects = new HashSet<>(Files.readAllLines(Path.of(args[1])));
    dependencies = new HashSet<>(Files.readAllLines(Path.of(args[2])));
    targets = new HashSet<>(Files.readAllLines(Path.of(args[3])));
    productPrefixes = Files.readAllLines(Path.of(args[4]));
    if (projects.isEmpty() || targets.isEmpty() || productPrefixes.isEmpty()) {
      throw new IllegalArgumentException("Project, target and product namespace boundaries are required");
    }
  }

  private boolean product(String holder) {
    for (String prefix : productPrefixes) if (holder.startsWith(prefix)) return true;
    return false;
  }

  private static String holder(DefinitionContext context) {
    if (context.isClassContext()) return context.asClassContext().getClassReference().getDescriptor();
    if (context.isFieldContext()) return context.asFieldContext().getFieldReference().getHolderClass().getDescriptor();
    if (context.isMethodContext()) return context.asMethodContext().getMethodReference().getHolderClass().getDescriptor();
    return "";
  }

  private static String reference(DefinitionContext context) {
    if (context.isClassContext()) return context.asClassContext().getClassReference().toString();
    if (context.isFieldContext()) return context.asFieldContext().getFieldReference().toString();
    if (context.isMethodContext()) return context.asMethodContext().getMethodReference().toString();
    return "unknown";
  }

  @Override
  public synchronized DiagnosticsLevel modifyDiagnosticsLevel(DiagnosticsLevel level, Diagnostic diagnostic) {
    if (!(diagnostic instanceof MissingDefinitionsDiagnostic)) return level;
    boolean fatalDiagnostic = false;
    Collection<MissingDefinitionInfo> missing = ((MissingDefinitionsDiagnostic) diagnostic).getMissingDefinitions();
    if (missing.isEmpty()) return level; // Unknown/empty diagnostics never become a success.
    for (MissingDefinitionInfo info : missing) {
      String owner = "", item = "", kind = "unknown";
      if (info.isMissingClass()) {
        kind = "class"; owner = info.asMissingClass().getClassReference().getDescriptor();
        item = info.asMissingClass().getClassReference().toString();
      } else if (info.isMissingField()) {
        kind = "field"; owner = info.asMissingField().getFieldReference().getHolderClass().getDescriptor();
        item = info.asMissingField().getFieldReference().toString();
      } else if (info.isMissingMethod()) {
        kind = "method"; owner = info.asMissingMethod().getMethodReference().getHolderClass().getDescriptor();
        item = info.asMissingMethod().getMethodReference().toString();
      }
      Set<String> reasons = new java.util.TreeSet<>();
      if (owner.isEmpty()) reasons.add("unknown-definition-kind");
      if (targets.contains(owner)) reasons.add("app-target-definition");
      if (product(owner)) reasons.add("product-namespace");
      List<Map<String, Object>> contexts = new ArrayList<>();
      // Use the complete API collection. CLI messages truncate after their first context.
      for (DefinitionContext context : info.getReferencedFromContexts()) {
        String source = holder(context);
        String role = projects.contains(source) ? "PROJECT" : dependencies.contains(source) ? "THIRD_PARTY_TEST" : "UNKNOWN";
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("holder", source); entry.put("reference", reference(context));
        entry.put("origin", context.getOrigin().toString()); entry.put("role", role); contexts.add(entry);
        if (role.equals("PROJECT")) reasons.add("project-reference");
        if (role.equals("UNKNOWN")) reasons.add("unknown-reference-origin");
        if (product(source)) reasons.add("product-owned-reference");
      }
      if (contexts.isEmpty()) reasons.add("missing-reference-context");
      contexts.sort(Comparator.comparing(e -> e.get("holder").toString() + e.get("reference").toString()));
      boolean fatal = !reasons.isEmpty();
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("kind", kind); entry.put("holder", owner); entry.put("reference", item);
      entry.put("message", info.getDiagnosticMessage()); entry.put("contexts", contexts);
      entry.put("classification", fatal ? "fatal-app-boundary" : "deferred-third-party-runtime");
      entry.put("fatalReasons", new ArrayList<>(reasons)); definitions.add(entry);
      if (fatal) { fatalCount++; fatalDiagnostic = true; } else { deferredCount++; }
    }
    // All non-missing diagnostics retain the official level. One protected context keeps this error fatal.
    return fatalDiagnostic ? DiagnosticsLevel.ERROR : DiagnosticsLevel.WARNING;
  }

  private static String json(Object value) {
    if (value == null) return "null";
    if (value instanceof Number || value instanceof Boolean) return value.toString();
    if (value instanceof Map) {
      List<String> parts = new ArrayList<>();
      for (Object raw : ((Map<?, ?>) value).entrySet()) {
        Map.Entry<?, ?> entry = (Map.Entry<?, ?>) raw;
        parts.add(json(entry.getKey().toString()) + ":" + json(entry.getValue()));
      }
      return "{" + String.join(",", parts) + "}";
    }
    if (value instanceof Collection) {
      List<String> parts = new ArrayList<>();
      for (Object item : (Collection<?>) value) parts.add(json(item));
      return "[" + String.join(",", parts) + "]";
    }
    StringBuilder result = new StringBuilder("\"");
    for (char c : value.toString().toCharArray()) {
      if (c == '"' || c == '\\') result.append('\\').append(c);
      else if (c < 32) result.append(String.format("\\u%04x", (int) c));
      else result.append(c);
    }
    return result.append('"').toString();
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 6) throw new IllegalArgumentException("Typed boundary inputs and TraceReferences arguments required");
    AndroidTestAppAbiTrace handler = new AndroidTestAppAbiTrace(args);
    boolean completed = false;
    try {
      // Public parser retains R8's standard strict CheckConsumer and narrow keep-rule formatter.
      TraceReferences.run(TraceReferencesCommand.parse(Arrays.copyOfRange(args, 5, args.length), Origin.unknown(), handler).build());
      completed = true;
    } finally {
      handler.definitions.sort(Comparator.comparing(e -> e.get("reference").toString()));
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("schema", 1); report.put("traceCompleted", completed);
      report.put("fatalCount", handler.fatalCount); report.put("deferredCount", handler.deferredCount);
      report.put("testRuntimeCompatibility", "not-verified"); report.put("missingDefinitions", handler.definitions);
      Files.writeString(Path.of(args[0]), json(report) + "\n", StandardCharsets.UTF_8);
    }
  }
}
