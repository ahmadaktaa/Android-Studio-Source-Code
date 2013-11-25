/*
 * Author: atotic
 * Created on Mar 23, 2004
 * License: Common Public License v1.0
 */
package com.jetbrains.python.debugger.pydev;

import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.jetbrains.python.console.pydev.PydevCompletionVariant;
import com.jetbrains.python.debugger.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class RemoteDebugger implements ProcessDebugger {
  private static final int RESPONSE_TIMEOUT = 60000;

  private static final Logger LOG = Logger.getInstance("#com.jetbrains.python.pydev.remote.RemoteDebugger");

  private static final String LOCAL_VERSION = "0.1";
  public static final String TEMP_VAR_PREFIX = "__py_debug_temp_var_";

  private static final SecureRandom ourRandom = new SecureRandom();

  private final IPyDebugProcess myDebugProcess;

  @NotNull
  private final ServerSocket myServerSocket;

  private final int myConnectionTimeout;
  private final Object mySocketObject = new Object(); // for synchronization on socket
  private Socket mySocket;
  private volatile boolean myConnected = false;
  private int mySequence = -1;
  private final Map<String, PyThreadInfo> myThreads = new ConcurrentHashMap<String, PyThreadInfo>();
  private final Map<Integer, ProtocolFrame> myResponseQueue = new HashMap<Integer, ProtocolFrame>();
  private final TempVarsHolder myTempVars = new TempVarsHolder();


  private final List<RemoteDebuggerCloseListener> myCloseListeners = ContainerUtil.createLockFreeCopyOnWriteList();
  private DebuggerReader myDebuggerReader;

  public RemoteDebugger(final IPyDebugProcess debugProcess, final ServerSocket serverSocket, final int timeout) {
    myDebugProcess = debugProcess;
    myServerSocket = serverSocket;
    myConnectionTimeout = timeout;
  }

  public IPyDebugProcess getDebugProcess() {
    return myDebugProcess;
  }

  @Override
  public boolean isConnected() {
    return myConnected;
  }


  @Override
  public void waitForConnect() throws Exception {
    try {
      //noinspection SocketOpenedButNotSafelyClosed
      myServerSocket.setSoTimeout(myConnectionTimeout);
      synchronized (mySocketObject) {
        mySocket = myServerSocket.accept();
        myConnected = true;
      }
    }
    finally {
      //it is closed in close() method on process termination
    }

    if (myConnected) {
      try {
        myDebuggerReader = new DebuggerReader();
        ApplicationManager.getApplication().executeOnPooledThread(myDebuggerReader);
      }
      catch (Exception e) {
        synchronized (mySocketObject) {
          mySocket.close();
        }
        throw e;
      }
    }
  }

  @Override
  public String handshake() throws PyDebuggerException {
    final VersionCommand command = new VersionCommand(this, LOCAL_VERSION, SystemInfo.isUnix ? "UNIX" : "WIN");
    command.execute();
    return command.getRemoteVersion();
  }

  @Override
  public PyDebugValue evaluate(final String threadId,
                               final String frameId,
                               final String expression, final boolean execute) throws PyDebuggerException {
    return evaluate(threadId, frameId, expression, execute, true);
  }


  @Override
  public PyDebugValue evaluate(final String threadId,
                               final String frameId,
                               final String expression,
                               final boolean execute,
                               boolean trimResult)
    throws PyDebuggerException {
    final EvaluateCommand command = new EvaluateCommand(this, threadId, frameId, expression, execute, trimResult);
    command.execute();
    return command.getValue();
  }

  @Override
  public void consoleExec(String threadId, String frameId, String expression, DebugCallback<String> callback) {
    final ConsoleExecCommand command = new ConsoleExecCommand(this, threadId, frameId, expression);
    command.execute(callback);
  }

  @Override
  public XValueChildrenList loadFrame(final String threadId, final String frameId) throws PyDebuggerException {
    final GetFrameCommand command = new GetFrameCommand(this, threadId, frameId);
    command.execute();
    return command.getVariables();
  }

  // todo: don't generate temp variables for qualified expressions - just split 'em
  @Override
  public XValueChildrenList loadVariable(final String threadId, final String frameId, final PyDebugValue var) throws PyDebuggerException {
    setTempVariable(threadId, frameId, var);
    final GetVariableCommand command = new GetVariableCommand(this, threadId, frameId, var);
    command.execute();
    return command.getVariables();
  }

  @Override
  public PyDebugValue changeVariable(final String threadId, final String frameId, final PyDebugValue var, final String value)
    throws PyDebuggerException {
    setTempVariable(threadId, frameId, var);
    return doChangeVariable(threadId, frameId, var.getEvaluationExpression(), value);
  }

  private PyDebugValue doChangeVariable(final String threadId, final String frameId, final String varName, final String value)
    throws PyDebuggerException {
    final ChangeVariableCommand command = new ChangeVariableCommand(this, threadId, frameId, varName, value);
    command.execute();
    return command.getNewValue();
  }

  @Override
  @Nullable
  public String loadSource(String path) {
    LoadSourceCommand command = new LoadSourceCommand(this, path);
    try {
      command.execute();
      return command.getContent();
    }
    catch (PyDebuggerException e) {
      return "#Couldn't load source of file " + path;
    }
  }

  private void cleanUp() {
    myThreads.clear();
    myResponseQueue.clear();
    mySequence = -1;
    myTempVars.clear();
  }

  // todo: change variable in lists doesn't work - either fix in pydevd or format var name appropriately
  private void setTempVariable(final String threadId, final String frameId, final PyDebugValue var) {
    final PyDebugValue topVar = var.getTopParent();
    if (myDebugProcess.isVariable(topVar.getName())) {
      return;
    }
    if (myTempVars.contains(threadId, frameId, topVar.getTempName())) {
      return;
    }

    topVar.setTempName(generateTempName());
    try {
      doChangeVariable(threadId, frameId, topVar.getTempName(), topVar.getName());
      myTempVars.put(threadId, frameId, topVar.getTempName());
    }
    catch (PyDebuggerException e) {
      LOG.error(e);
      topVar.setTempName(null);
    }
  }

  private void clearTempVariables(final String threadId) {
    final Map<String, Set<String>> threadVars = myTempVars.get(threadId);
    if (threadVars == null || threadVars.size() == 0) return;

    for (Map.Entry<String, Set<String>> entry : threadVars.entrySet()) {
      final Set<String> frameVars = entry.getValue();
      if (frameVars == null || frameVars.size() == 0) continue;

      final String expression = "del " + StringUtil.join(frameVars, ",");
      try {
        evaluate(threadId, entry.getKey(), expression, true);
      }
      catch (PyDebuggerException e) {
        LOG.error(e);
      }
    }

    myTempVars.clear(threadId);
  }

  private static String generateTempName() {
    return TEMP_VAR_PREFIX + ourRandom.nextInt(Integer.MAX_VALUE);
  }

  @Override
  public Collection<PyThreadInfo> getThreads() {
    return Collections.unmodifiableCollection(new ArrayList<PyThreadInfo>(myThreads.values()));
  }

  int getNextSequence() {
    mySequence += 2;
    return mySequence;
  }

  void placeResponse(final int sequence, final ProtocolFrame response) {
    synchronized (myResponseQueue) {
      if (response == null || myResponseQueue.containsKey(sequence)) {
        myResponseQueue.put(sequence, response);
      }
      if (response != null) {
        myResponseQueue.notifyAll();
      }
    }
  }

  @Nullable
  ProtocolFrame waitForResponse(final int sequence) {
    ProtocolFrame response;
    long until = System.currentTimeMillis() + RESPONSE_TIMEOUT;

    synchronized (myResponseQueue) {
      do {
        try {
          myResponseQueue.wait(1000);
        }
        catch (InterruptedException ignore) {
        }
        response = myResponseQueue.get(sequence);
      }
      while (response == null && isConnected() && System.currentTimeMillis() < until);
      myResponseQueue.remove(sequence);
    }

    return response;
  }

  @Override
  public void execute(@NotNull final AbstractCommand command) {
    if (command instanceof ResumeOrStepCommand) {
      final String threadId = ((ResumeOrStepCommand)command).getThreadId();
      clearTempVariables(threadId);
    }

    try {
      command.execute();
    }
    catch (PyDebuggerException e) {
      LOG.error(e);
    }
  }

  boolean sendFrame(final ProtocolFrame frame) {
    logFrame(frame, true);

    try {
      final byte[] packed = frame.pack();
      synchronized (mySocketObject) {
        final OutputStream os = mySocket.getOutputStream();
        os.write(packed);
        os.flush();
        return true;
      }
    }
    catch (SocketException se) {
      disconnect();
      fireCommunicationError();
    }
    catch (IOException e) {
      LOG.error(e);
    }
    return false;
  }

  private static void logFrame(ProtocolFrame frame, boolean out) {
    if (LOG.isDebugEnabled()) {
      LOG.debug(String.format("%1$tH:%1$tM:%1$tS.%1$tL %2$s %3$s\n", new Date(), (out ? "<<<" : ">>>"), frame));
    }
  }

  @Override
  public void suspendAllThreads() {
    for (PyThreadInfo thread : getThreads()) {
      suspendThread(thread.getId());
    }
  }


  @Override
  public void suspendThread(String threadId) {
    final SuspendCommand command = new SuspendCommand(this, threadId);
    execute(command);
  }

  @Override
  public void close() {
    if (!myServerSocket.isClosed()) {
      try {
        myServerSocket.close();
      }
      catch (IOException e) {
        LOG.warn("Error closing socket", e);
      }
    }
    if (myDebuggerReader != null) {
      myDebuggerReader.close();
    }
    fireCloseEvent();
  }

  @Override
  public void disconnect() {
    synchronized (mySocketObject) {
      myConnected = false;

      if (mySocket != null && !mySocket.isClosed()) {
        try {
          mySocket.close();
        }
        catch (IOException ignore) {
        }
      }
    }

    cleanUp();
  }

  @Override
  public void run() throws PyDebuggerException {
    new RunCommand(this).execute();
  }

  @Override
  public void smartStepInto(String threadId, String functionName) {
    final SmartStepIntoCommand command = new SmartStepIntoCommand(this, threadId, functionName);
    execute(command);
  }

  @Override
  public void resumeOrStep(String threadId, ResumeOrStepCommand.Mode mode) {
    final ResumeOrStepCommand command = new ResumeOrStepCommand(this, threadId, mode);
    execute(command);
  }

  @Override
  public void setTempBreakpoint(String type, String file, int line) {
    final SetBreakpointCommand command =
      new SetBreakpointCommand(this, type, file, line);
    execute(command);  // set temp. breakpoint
  }

  @Override
  public void removeTempBreakpoint(String file, int line) {
    final RemoveBreakpointCommand command = new RemoveBreakpointCommand(this, "all", file, line);
    execute(command);  // remove temp. breakpoint
  }

  @Override
  public void setBreakpoint(String typeId, String file, int line, String condition, String logExpression) {
    final SetBreakpointCommand command =
      new SetBreakpointCommand(this, typeId, file, line,
                               condition,
                               logExpression);
    execute(command);
  }

  @Override
  public void removeBreakpoint(String typeId, String file, int line) {
    final RemoveBreakpointCommand command =
      new RemoveBreakpointCommand(this, typeId, file, line);
    execute(command);
  }

  private class DebuggerReader implements Runnable {
    private final InputStream myInputStream;
    private boolean myClosing = false;

    private DebuggerReader() throws IOException {
      synchronized (mySocketObject) {
        this.myInputStream = mySocket.getInputStream();
      }
    }

    public void run() {
      final BufferedReader reader = new BufferedReader(new InputStreamReader(myInputStream, Charset.forName("UTF-8")));
      try {
        String line;
        while ((line = reader.readLine()) != null) {
          processResponse(line);
          if (myClosing) {
            break;
          }
        }
      }
      catch (SocketException ignore) {
        fireCommunicationError();
      }
      catch (Exception e) {
        LOG.error(e);
      }
      finally {
        closeReader(reader);
        fireExitEvent();
      }
    }

    private void processResponse(final String line) {
      try {
        final ProtocolFrame frame = new ProtocolFrame(line);
        logFrame(frame, false);

        if (AbstractThreadCommand.isThreadCommand(frame.getCommand())) {
          processThreadEvent(frame);
        }
        else if (AbstractCommand.isWriteToConsole(frame.getCommand())) {
          writeToConsole(ProtocolParser.parseIo(frame.getPayload()));
        }
        else if (AbstractCommand.isExitEvent(frame.getCommand())) {
          fireCommunicationError();
        }
        else if (AbstractCommand.isCallSignatureTrace(frame.getCommand())) {
          recordCallSignature(ProtocolParser.parseCallSignature(frame.getPayload()));
        }
        else {
          placeResponse(frame.getSequence(), frame);
        }
      }
      catch (Throwable t) {
        // shouldn't interrupt reader thread
        LOG.error(t);
      }
    }

    private void recordCallSignature(PySignature signature) {
      myDebugProcess.recordSignature(signature);
    }

    // todo: extract response processing
    private void processThreadEvent(ProtocolFrame frame) throws PyDebuggerException {
      switch (frame.getCommand()) {
        case AbstractCommand.CREATE_THREAD: {
          final PyThreadInfo thread = parseThreadEvent(frame);
          if (!thread.isPydevThread()) {  // ignore pydevd threads
            myThreads.put(thread.getId(), thread);
          }
          break;
        }
        case AbstractCommand.SUSPEND_THREAD: {
          final PyThreadInfo event = parseThreadEvent(frame);
          final PyThreadInfo thread = myThreads.get(event.getId());
          if (thread != null) {
            thread.updateState(PyThreadInfo.State.SUSPENDED, event.getFrames());
            thread.setStopReason(event.getStopReason());
            thread.setMessage(event.getMessage());
            myDebugProcess.threadSuspended(thread);
          }
          break;
        }
        case AbstractCommand.RESUME_THREAD: {
          final String id = ProtocolParser.getThreadId(frame.getPayload());
          final PyThreadInfo thread = myThreads.get(id);
          if (thread != null) {
            thread.updateState(PyThreadInfo.State.RUNNING, null);
            myDebugProcess.threadResumed(thread);
          }
          break;
        }
        case AbstractCommand.KILL_THREAD: {
          final String id = frame.getPayload();
          final PyThreadInfo thread = myThreads.get(id);
          if (thread != null) {
            thread.updateState(PyThreadInfo.State.KILLED, null);
            myThreads.remove(id);
          }
          break;
        }
      }
    }

    private PyThreadInfo parseThreadEvent(ProtocolFrame frame) throws PyDebuggerException {
      return ProtocolParser.parseThread(frame.getPayload(), myDebugProcess.getPositionConverter());
    }

    private void closeReader(BufferedReader reader) {
      try {
        reader.close();
      }
      catch (IOException ignore) {
      }
    }

    public void close() {
      myClosing = true;
    }
  }

  private void writeToConsole(PyIo io) {
    ConsoleViewContentType contentType;
    if (io.getCtx() == 2) {
      contentType = ConsoleViewContentType.ERROR_OUTPUT;
    }
    else {
      contentType = ConsoleViewContentType.NORMAL_OUTPUT;
    }
    myDebugProcess.printToConsole(io.getText(), contentType);
  }


  private static class TempVarsHolder {
    private final Map<String, Map<String, Set<String>>> myData = new HashMap<String, Map<String, Set<String>>>();

    public boolean contains(final String threadId, final String frameId, final String name) {
      final Map<String, Set<String>> threadVars = myData.get(threadId);
      if (threadVars == null) return false;

      final Set<String> frameVars = threadVars.get(frameId);
      if (frameVars == null) return false;

      return frameVars.contains(name);
    }

    private void put(final String threadId, final String frameId, final String name) {
      Map<String, Set<String>> threadVars = myData.get(threadId);
      if (threadVars == null) myData.put(threadId, (threadVars = new HashMap<String, Set<String>>()));

      Set<String> frameVars = threadVars.get(frameId);
      if (frameVars == null) threadVars.put(frameId, (frameVars = new HashSet<String>()));

      frameVars.add(name);
    }

    private Map<String, Set<String>> get(final String threadId) {
      return myData.get(threadId);
    }

    private void clear() {
      myData.clear();
    }

    private void clear(final String threadId) {
      final Map<String, Set<String>> threadVars = myData.get(threadId);
      if (threadVars != null) {
        threadVars.clear();
      }
    }
  }

  public void addCloseListener(RemoteDebuggerCloseListener listener) {
    myCloseListeners.add(listener);
  }

  public void removeCloseListener(RemoteDebuggerCloseListener listener) {
    myCloseListeners.remove(listener);
  }

  @Override
  public List<PydevCompletionVariant> getCompletions(String threadId, String frameId, String prefix) {
    final GetCompletionsCommand command = new GetCompletionsCommand(this, threadId, frameId, prefix);
    execute(command);
    return command.getCompletions();
  }

  @Override
  public void addExceptionBreakpoint(ExceptionBreakpointCommandFactory factory) {
    execute(factory.createAddCommand(this));
  }

  @Override
  public void removeExceptionBreakpoint(ExceptionBreakpointCommandFactory factory) {
    execute(factory.createRemoveCommand(this));
  }

  private void fireCloseEvent() {
    for (RemoteDebuggerCloseListener listener : myCloseListeners) {
      listener.closed();
    }
  }

  private void fireCommunicationError() {
    for (RemoteDebuggerCloseListener listener : myCloseListeners) {
      listener.communicationError();
    }
  }

  private void fireExitEvent() {
    for (RemoteDebuggerCloseListener listener : myCloseListeners) {
      listener.detached();
    }
  }
}