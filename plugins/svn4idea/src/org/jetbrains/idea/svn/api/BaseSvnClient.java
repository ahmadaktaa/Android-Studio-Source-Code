package org.jetbrains.idea.svn.api;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.VcsException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn.SvnVcs;
import org.jetbrains.idea.svn.WorkingCopyFormat;
import org.jetbrains.idea.svn.auth.AuthenticationService;
import org.jetbrains.idea.svn.commandLine.*;
import org.jetbrains.idea.svn.diff.DiffOptions;
import org.tmatesoft.svn.core.SVNCancelException;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.wc.ISVNEventHandler;
import org.tmatesoft.svn.core.wc.SVNDiffOptions;
import org.tmatesoft.svn.core.wc.SVNEvent;
import org.tmatesoft.svn.core.wc.SVNRevision;
import org.tmatesoft.svn.core.wc2.SvnTarget;

import java.io.File;
import java.util.Collection;
import java.util.List;

/**
 * @author Konstantin Kolosovsky.
 */
public abstract class BaseSvnClient implements SvnClient {
  protected SvnVcs myVcs;
  protected ClientFactory myFactory;
  protected boolean myIsActive;

  @NotNull
  @Override
  public SvnVcs getVcs() {
    return myVcs;
  }

  @Override
  public void setVcs(@NotNull SvnVcs vcs) {
    myVcs = vcs;
  }

  @NotNull
  @Override
  public ClientFactory getFactory() {
    return myFactory;
  }

  @Override
  public void setFactory(@NotNull ClientFactory factory) {
    myFactory = factory;
  }

  @Override
  public void setIsActive(boolean isActive) {
    myIsActive = isActive;
  }

  protected void assertUrl(@NotNull SvnTarget target) {
    if (!target.isURL()) {
      throw new IllegalArgumentException("Target should be url " + target);
    }
  }

  protected void assertFile(@NotNull SvnTarget target) {
    if (!target.isFile()) {
      throw new IllegalArgumentException("Target should be file " + target);
    }
  }

  protected void assertDirectory(@NotNull SvnTarget target) {
    assertFile(target);
    if (!target.getFile().isDirectory()) {
      throw new IllegalArgumentException("Target should be directory " + target);
    }
  }

  protected void validateFormat(@NotNull WorkingCopyFormat format, @NotNull Collection<WorkingCopyFormat> supported) throws VcsException {
    if (!supported.contains(format)) {
      throw new VcsException(
        String.format("%s format is not supported. Supported formats are: %s.", format.getName(), StringUtil.join(supported, ",")));
    }
  }

  @NotNull
  public CommandExecutor execute(@NotNull SvnVcs vcs,
                                 @NotNull SvnTarget target,
                                 @NotNull SvnCommandName name,
                                 @NotNull List<String> parameters,
                                 @Nullable LineCommandListener listener) throws SvnBindException {
    return execute(vcs, target, null, name, parameters, listener);
  }

  @NotNull
  public CommandExecutor execute(@NotNull SvnVcs vcs,
                                 @NotNull SvnTarget target,
                                 @Nullable File workingDirectory,
                                 @NotNull SvnCommandName name,
                                 @NotNull List<String> parameters,
                                 @Nullable LineCommandListener listener) throws SvnBindException {
    Command command = new Command(name);

    command.setTarget(target);
    command.setWorkingDirectory(workingDirectory);
    command.setResultBuilder(listener);
    command.put(parameters);

    CommandRuntime runtime = new CommandRuntime(vcs, new AuthenticationService(vcs, myIsActive));
    return runtime.runWithAuthenticationAttempt(command);
  }

  protected static void callHandler(@Nullable ProgressTracker handler, @NotNull ProgressEvent event) throws VcsException {
    if (handler != null) {
      try {
        handler.consume(event);
      }
      catch (SVNException e) {
        throw new SvnBindException(e);
      }
    }
  }

  @NotNull
  protected static ProgressEvent createEvent(@NotNull File path, @Nullable EventAction action) {
    return new ProgressEvent(path, 0, null, null, action, null, null);
  }

  @Nullable
  protected static ISVNEventHandler toEventHandler(@Nullable final ProgressTracker handler) {
    ISVNEventHandler result = null;

    if (handler != null) {
      result = new ISVNEventHandler() {
        @Override
        public void handleEvent(SVNEvent event, double progress) throws SVNException {
          handler.consume(ProgressEvent.create(event));
        }

        @Override
        public void checkCancelled() throws SVNCancelException {
          handler.checkCancelled();
        }
      };
    }

    return result;
  }

  @Nullable
  protected static SVNDiffOptions toDiffOptions(@Nullable DiffOptions options) {
    return options != null ? new SVNDiffOptions(options.isIgnoreAllWhitespace(), options.isIgnoreAmountOfWhitespace(),
                                                options.isIgnoreEOLStyle()) : null;
  }

  @Nullable
  protected static SVNDepth toDepth(@Nullable Depth depth) {
    return depth != null ? SVNDepth.fromString(depth.getName()) : null;
  }

  @NotNull
  protected static SVNRevision notNullize(@Nullable SVNRevision revision) {
    return revision != null ? revision : SVNRevision.UNDEFINED;
  }
}
