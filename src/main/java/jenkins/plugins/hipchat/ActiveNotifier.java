package jenkins.plugins.hipchat;

import hudson.Util;
import hudson.model.*;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.ChangeLogSet.Entry;
import org.apache.commons.lang.StringUtils;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

@SuppressWarnings("rawtypes")
public class ActiveNotifier implements FineGrainedNotifier {

    private static final Logger logger = Logger.getLogger(HipChatListener.class.getName());

    HipChatNotifier notifier;

    public ActiveNotifier(HipChatNotifier notifier) {
        super();
        this.notifier = notifier;
    }

    private HipChatService getHipChat(AbstractBuild r) {
        AbstractProject<?, ?> project = r.getProject();
        String projectRoom = Util.fixEmpty(project.getProperty(HipChatNotifier.HipChatJobProperty.class).getRoom());
        return notifier.newHipChatService(projectRoom);
    }

    public void deleted(AbstractBuild r) {
    }

    public void started(AbstractBuild build) {
        String changes = getChanges(build);
        CauseAction cause = build.getAction(CauseAction.class);

        if (changes != null) {
            notifyStart(build, changes);
        } else if (cause != null) {
            MessageBuilder message = new MessageBuilder(notifier, build);
            message.append(cause.getShortDescription());
            notifyStart(build, message.appendOpenLink().toString());
        } else {
            notifyStart(build, getBuildStatusMessage(build));
        }
    }

    private void notifyStart(AbstractBuild build, String message) {
        getHipChat(build).publish(message, "green");
    }

    public void finalized(AbstractBuild r) {
    }

    public void completed(AbstractBuild r) {
        AbstractProject<?, ?> project = r.getProject();
        HipChatNotifier.HipChatJobProperty jobProperty = project.getProperty(HipChatNotifier.HipChatJobProperty.class);
        Result result = r.getResult();
        AbstractBuild<?, ?> previousBuild = project.getLastBuild().getPreviousBuild();
        Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
        if ((result == Result.ABORTED && jobProperty.getNotifyAborted())
                || (result == Result.FAILURE && jobProperty.getNotifyFailure())
                || (result == Result.NOT_BUILT && jobProperty.getNotifyNotBuilt())
                || (result == Result.SUCCESS && previousResult == Result.FAILURE && jobProperty.getNotifyBackToNormal())
                || (result == Result.SUCCESS && jobProperty.getNotifySuccess())
                || (result == Result.UNSTABLE && jobProperty.getNotifyUnstable())) {
            getHipChat(r).publish(getBuildStatusMessage(r), getBuildColor(r));
        }
    }

    String getChanges(AbstractBuild r) {
        if (!r.hasChangeSetComputed()) {
            logger.info("No change set computed...");
            return null;
        }
        ChangeLogSet changeSet = r.getChangeSet();
        List<Entry> entries = new LinkedList<Entry>();
        Set<AffectedFile> files = new HashSet<AffectedFile>();
        for (Object o : changeSet.getItems()) {
            Entry entry = (Entry) o;
            logger.info("Entry " + o);
            entries.add(entry);
            files.addAll(entry.getAffectedFiles());
        }
        if (entries.isEmpty()) {
            logger.info("Empty change...");
            return null;
        }
        Set<String> authors = new HashSet<String>();
        for (Entry entry : entries) {
            authors.add(entry.getAuthor().getDisplayName());
        }
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.append("Started by changes from ");
        message.append(StringUtils.join(authors, ", "));
        message.append(" (");
        message.append(files.size());
        message.append(" file(s) changed) ");
        return message.appendOpenLink().toString();
    }

    static String getBuildColor(AbstractBuild r) {
        Result result = r.getResult();
        if (result == Result.SUCCESS) {
            return "green";
        } else if (result == Result.FAILURE) {
            return "red";
        } else {
            return "yellow";
        }
    }

    String getBuildStatusMessage(AbstractBuild r) {
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.appendStatusMessage();
        message.appendDuration();
        return message.appendOpenLink().toString();
    }

    public static class MessageBuilder {
        private StringBuffer message;
        private HipChatNotifier notifier;
        private AbstractBuild build;

        public MessageBuilder(HipChatNotifier notifier, AbstractBuild build) {
            this.notifier = notifier;
            this.message = new StringBuffer();
            this.build = build;
            startMessage();
        }

        public MessageBuilder appendStatusMessage() {
            message.append(getStatusMessage(build));
            return this;
        }

        static String getStatusMessage(AbstractBuild r) {
            if (r.isBuilding()) {
                return "Starting...";
            }
            Result result = r.getResult();
            Run previousBuild = r.getProject().getLastBuild().getPreviousBuild();
            Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
            if (result == Result.SUCCESS && previousResult == Result.FAILURE) return "Back to normal";
            if (result == Result.SUCCESS) return "Success";
            if (result == Result.FAILURE) return "<b>FAILURE</b>";
            if (result == Result.ABORTED) return "ABORTED";
            if (result == Result.NOT_BUILT) return "Not built";
            if (result == Result.UNSTABLE) return "Unstable";
            return "Unknown";
        }

        public MessageBuilder append(String string) {
            message.append(string);
            return this;
        }

        public MessageBuilder append(Object string) {
            message.append(string.toString());
            return this;
        }

        private MessageBuilder startMessage() {
            Result result = build.getResult();
            message.append((result == Result.FAILURE) ? getRandomFailEmoji() : getRandomWinEmoji());
            message.append(build.getProject().getDisplayName());
            message.append(" - ");
            message.append(build.getDisplayName());
            message.append(" ");
            List<Entry> entries = new LinkedList<Entry>();
            Set<String> authors = new HashSet<String>();
            Set<AffectedFile> files = new HashSet<AffectedFile>();
            ChangeLogSet changeSet = build.getChangeSet();
            for (Object o : changeSet.getItems()) {
                Entry entry = (Entry) o;
                logger.info("Entry " + o);
                entries.add(entry);
                files.addAll(entry.getAffectedFiles());
            }
            if (entries.isEmpty()) {
                logger.info("Empty change...");
                return null;
            }
            for (Entry entry : entries) {
                authors.add(entry.getAuthor().getDisplayName());
            }
            message.append("Started by changes from ");
            message.append(StringUtils.join(authors, ", "));
            message.append(" (");
            message.append(files.size());
            message.append(" file(s) changed)");
            message.append(" - ");
            return this;
        }

        public MessageBuilder appendOpenLink() {
            Result result = build.getResult();
            String url = notifier.getBuildServerUrl() + build.getUrl();
            message.append(" (<a href='").append(url).append("'>Open</a>)");
            message.append((result == Result.FAILURE) ? getRandomFailEmoji() : getRandomWinEmoji());
            return this;
        }

        public MessageBuilder appendDuration() {
            Result result = build.getResult();
            message.append(" after ");
            message.append(build.getDurationString());
            message.append((result == Result.FAILURE) ? getRandomFailEmoji() : getRandomWinEmoji());
            return this;
        }

        public String toString() {
            return message.toString();
        }

        public String getRandomFailEmoji() {
            List<String> fails = new LinkedList<String>();
            fails.add(" (tableflip) ");
            fails.add(" (yuno) ");
            fails.add(" (rageguy) ");
            fails.add(" (areyoukiddingme) ");
            fails.add(" (boom) ");
            fails.add(" (cerealspit) ");
            fails.add(" (derp) ");
            fails.add(" (disapproval) ");
            fails.add(" (facepalm) ");
            fails.add(" (failed) ");
            fails.add(" (grumpycat) ");
            fails.add(" (gtfo) ");
            fails.add(" (omg) ");
            fails.add(" (ohgodwhy) ");
            fails.add(" (oops) ");
            fails.add(" (poo) ");
            fails.add(" (sadpanda) ");
            fails.add(" (sadtroll) ");
            fails.add(" (scumbag) ");
            fails.add(" (stare) ");
            fails.add(" (thumbsdown) ");
            fails.add(" (wat) ");
            fails.add(" (wtf) ");
            fails.add(" >:-( ");
            int i = new Random().nextInt(fails.size());
            return fails.get(i);
        }

        public String getRandomWinEmoji() {
            List<String> wins = new LinkedList<String>();
            wins.add(" (allthethings) ");
            wins.add(" (awthanks) ");
            wins.add(" (awyeah) ");
            wins.add(" (cake) ");
            wins.add(" (challengeaccepted) ");
            wins.add(" (content) ");
            wins.add(" (dance) ");
            wins.add(" (fonzie) ");
            wins.add(" (fuckyeah) ");
            wins.add(" (gangnamsytle) ");
            wins.add(" (goodnews) ");
            wins.add(" (notbad) ");
            wins.add(" (beer) ");
            wins.add(" (pbr) ");
            wins.add(" (shrug) ");
            wins.add(" (success) ");
            wins.add(" (successful) ");
            wins.add(" (thumbsup) ");
            wins.add(" (yey) ");
            wins.add(" (yougotitdude) ");
            wins.add(" (dealwithit) ");
            int i = new Random().nextInt(wins.size());
            return wins.get(i);
        }
    }
}
