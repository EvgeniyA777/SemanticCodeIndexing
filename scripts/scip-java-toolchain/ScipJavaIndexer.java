import com.sourcegraph.Scip;
import com.sourcegraph.scip_semanticdb.MavenPackage;
import com.sourcegraph.scip_semanticdb.ScipOutputFormat;
import com.sourcegraph.scip_semanticdb.ScipSemanticdb;
import com.sourcegraph.scip_semanticdb.ScipSemanticdbOptions;
import com.sourcegraph.scip_semanticdb.ScipSemanticdbReporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal driver for the repo-managed Java SCIP toolchain (plans/018 Stage 4).
 *
 * <p>{@code scip-semanticdb} converts SemanticDB files into a SCIP index but
 * ships no command-line entry point, and the full {@code scip-java} CLI is a
 * Scala artifact that drags in coursier and an embedded Kotlin compiler. This
 * class is the smallest thing that closes that gap: it calls
 * {@link ScipSemanticdb#run} and nothing else.
 *
 * <p>It is deliberately a separate process rather than a semidx runtime
 * dependency. Mirroring ADR-047 and the Stage 3 TypeScript provider, the
 * toolchain resolves externally and never joins the semidx classpath, so its
 * protobuf runtime cannot collide with the one semidx already uses for gRPC.
 *
 * <pre>
 * Usage: ScipJavaIndexer &lt;targetroot&gt; &lt;sourceroot&gt; &lt;output.scip&gt; [--scrub-project-root]
 * </pre>
 *
 * <p>{@code --scrub-project-root} rewrites {@code metadata.project_root} to the
 * empty string after the index is written. A raw {@code .scip} embeds the
 * absolute path of the machine that produced it, which must never land in a
 * committed fixture. It is opt-in so that only fixture regeneration pays for it;
 * ordinary provider runs leave the artifact untouched.
 */
public final class ScipJavaIndexer {

  private ScipJavaIndexer() {}

  /** Reporter that prints every problem and remembers that one happened. */
  private static final class FailingReporter extends ScipSemanticdbReporter {
    private boolean failed = false;

    @Override
    public void error(String message) {
      failed = true;
      System.err.println("scip-semanticdb: " + message);
    }

    @Override
    public void error(Throwable throwable) {
      failed = true;
      System.err.println("scip-semanticdb: " + throwable);
      throwable.printStackTrace(System.err);
    }

    boolean failed() {
      return failed;
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length < 3) {
      System.err.println(
          "usage: ScipJavaIndexer <targetroot> <sourceroot> <output.scip> [--scrub-project-root]");
      System.exit(2);
    }

    Path targetroot = Paths.get(args[0]).toAbsolutePath();
    Path sourceroot = Paths.get(args[1]).toAbsolutePath();
    Path output = Paths.get(args[2]).toAbsolutePath();
    boolean scrubProjectRoot = false;
    for (int i = 3; i < args.length; i++) {
      if ("--scrub-project-root".equals(args[i])) {
        scrubProjectRoot = true;
      } else {
        System.err.println("unknown argument: " + args[i]);
        System.exit(2);
      }
    }

    if (!Files.isDirectory(targetroot)) {
      System.err.println("targetroot is not a directory: " + targetroot);
      System.exit(1);
    }

    FailingReporter reporter = new FailingReporter();
    ScipSemanticdbOptions options =
        new ScipSemanticdbOptions(
            Collections.singletonList(targetroot),
            output,
            sourceroot,
            reporter,
            Scip.ToolInfo.newBuilder().setName("scip-java").setVersion("0.12.3").build(),
            ScipOutputFormat.TYPED_PROTOBUF,
            /* parallel= */ false,
            new ArrayList<MavenPackage>(),
            /* emitInverseRelationships= */ false,
            /* allowEmptyIndex= */ true,
            /* allowExportingGlobalSymbolsFromDirectoryEntries= */ false);

    ScipSemanticdb.run(options);

    if (reporter.failed() || reporter.hasErrors()) {
      System.err.println("scip-semanticdb reported errors; refusing to claim a usable index");
      System.exit(1);
    }

    if (scrubProjectRoot) {
      scrubProjectRoot(output);
    }

    System.out.println("scip_java_index=" + output);
  }

  /**
   * Rewrite the index in place with an empty {@code metadata.project_root},
   * preserving documents, symbols, and occurrences exactly.
   */
  private static void scrubProjectRoot(Path index) throws IOException {
    byte[] bytes = Files.readAllBytes(index);
    Scip.Index parsed = Scip.Index.parseFrom(bytes);
    Scip.Index scrubbed =
        parsed.toBuilder()
            .setMetadata(parsed.getMetadata().toBuilder().setProjectRoot("").build())
            .build();
    Files.write(index, scrubbed.toByteArray());
    System.out.println("scip_java_project_root_scrubbed=true");
  }
}
