import hudson.remoting.Launcher;
import io.jenkins.remoting.shaded.org.kohsuke.args4j.CmdLineParser;
import java.nio.file.Files;
import java.nio.file.Path;

// Uses the actual controller-provided remoting jar; never initializes/connects an agent.
class AgentSecretFileCheck {
    public static void main(String[] args) throws Exception {
        Launcher launcher = new Launcher();
        new CmdLineParser(launcher).parseArgument("-secret", "@" + args[0]);
        if (!Files.readString(Path.of(args[0])).trim().equals(launcher.secret)) {
            throw new IllegalStateException("Node secret file argument was not expanded");
        }
        System.out.println("AGENT_SECRET_FILE PASS parser=actual_remoting secret_logged=false agent_started=false");
    }
}
