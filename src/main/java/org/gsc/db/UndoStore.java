package org.gsc.db;

import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.gsc.common.exception.RevokingStoreIllegalStateException;
import org.gsc.db.storage.SourceInter;
import org.gsc.net.discover.dht.Utils;
import org.iq80.leveldb.WriteOptions;
import org.springframework.stereotype.Component;

@Slf4j
@Getter // only for unit test
@Component
public class UndoStore implements IUndoStore {

  private static final int DEFAULT_STACK_MAX_SIZE = 256;

  private Deque<UndoState> stack = new LinkedList<>();
  private boolean disabled = true;
  private int activeDialog = 0;
  private AtomicInteger maxSize = new AtomicInteger(DEFAULT_STACK_MAX_SIZE);
  private WriteOptions writeOptions = new WriteOptions().sync(true);

  @Override
  public Dialog buildDialog() {
    return buildDialog(false);
  }

  @Override
  public synchronized Dialog buildDialog(boolean forceEnable) {
    if (disabled && !forceEnable) {
      return new Dialog(this);
    }

    boolean disableOnExit = disabled && forceEnable;
    if (forceEnable) {
      disabled = false;
    }

    while (stack.size() > maxSize.get()) {
      stack.poll();
    }

    stack.add(new UndoState());
    ++activeDialog;
    return new Dialog(this, disableOnExit);
  }

  @Override
  public synchronized void onCreate(UndoTuple tuple, byte[] value) {
    if (disabled) {
      return;
    }

    addIfEmtpy();
    UndoState state = stack.peekLast();
    state.newIds.add(tuple);
  }

  @Override
  public synchronized void onModify(UndoTuple tuple, byte[] value) {
    if (disabled) {
      return;
    }

    addIfEmtpy();
    UndoState state = stack.peekLast();
    if (state.newIds.contains(tuple) || state.oldValues.containsKey(tuple)) {
      return;
    }

    state.oldValues.put(tuple, Utils.clone(value));
  }

  @Override
  public synchronized void onRemove(UndoTuple tuple, byte[] value) {
    if (disabled) {
      return;
    }

    addIfEmtpy();
    UndoState state = stack.peekLast();
    if (state.newIds.contains(tuple)) {
      state.newIds.remove(tuple);
      return;
    }

    if (state.oldValues.containsKey(tuple)) {
      state.removed.put(tuple, state.oldValues.get(tuple));
      state.oldValues.remove(tuple);
      return;
    }

    if (state.removed.containsKey(tuple)) {
      return;
    }

    state.removed.put(tuple, Utils.clone(value));
  }

  @Override
  public synchronized void merge() throws RevokingStoreIllegalStateException {
    if (activeDialog <= 0) {
      throw new RevokingStoreIllegalStateException("activeDialog has to be greater than 0");
    }

    if (activeDialog == 1 && stack.size() == 1) {
      stack.pollLast();
      --activeDialog;
      return;
    }

    if (stack.size() < 2) {
      return;
    }

    UndoState state = stack.peekLast();
    @SuppressWarnings("unchecked")
    List<UndoState> list = (List<UndoState>) stack;
    UndoState prevState = list.get(stack.size() - 2);

    state.oldValues.entrySet().stream()
        .filter(e -> !prevState.newIds.contains(e.getKey()))
        .filter(e -> !prevState.oldValues.containsKey(e.getKey()))
        .forEach(e -> prevState.oldValues.put(e.getKey(), e.getValue()));

    prevState.newIds.addAll(state.newIds);

    state.removed.entrySet().stream()
        .filter(e -> {
          boolean has = prevState.newIds.contains(e.getKey());
          if (has) {
            prevState.newIds.remove(e.getKey());
          }

          return !has;
        })
        .filter(e -> {
          boolean has = prevState.oldValues.containsKey(e.getKey());
          if (has) {
            prevState.removed.put(e.getKey(), e.getValue());
            prevState.oldValues.remove(e.getKey());
          }

          return !has;
        })
        .forEach(e -> prevState.removed.put(e.getKey(), e.getValue()));

    stack.pollLast();
    --activeDialog;
  }

  @Override
  public synchronized void revoke() throws RevokingStoreIllegalStateException {
    if (disabled) {
      return;
    }

    if (activeDialog <= 0) {
      throw new RevokingStoreIllegalStateException("activeDialog has to be greater than 0");
    }

    disabled = true;

    try {
      UndoState state = stack.peekLast();
      if (Objects.isNull(state)) {
        return;
      }

      state.oldValues.forEach((k, v) -> k.database.putData(k.key, v));
      state.newIds.forEach(e -> e.database.deleteData(e.key));
      state.removed.forEach((k, v) -> k.database.putData(k.key, v));
      stack.pollLast();
    } finally {
      disabled = false;
    }
    --activeDialog;
  }

  @Override
  public synchronized void commit() throws RevokingStoreIllegalStateException {
    if (activeDialog <= 0) {
      throw new RevokingStoreIllegalStateException("activeDialog has to be greater than 0");
    }

    --activeDialog;
  }

  @Override
  public synchronized void pop() throws RevokingStoreIllegalStateException {
    if (activeDialog != 0) {
      throw new RevokingStoreIllegalStateException("activeDialog has to be equal 0");
    }

    if (stack.isEmpty()) {
      throw new RevokingStoreIllegalStateException("stack is empty");
    }

    disabled = true;

    try {
      UndoState state = stack.peekLast();
      state.oldValues.forEach((k, v) -> k.database.putData(k.key, v, writeOptions));
      state.newIds.forEach(e -> e.database.deleteData(e.key, writeOptions));
      state.removed.forEach((k, v) -> k.database.putData(k.key, v, writeOptions));
      stack.pollLast();
    } finally {
      disabled = false;
    }
  }

  @Override
  public synchronized UndoState head() {
    if (stack.isEmpty()) {
      return null;
    }

    return stack.peekLast();
  }

  @Override
  public synchronized void enable() {
    disabled = false;
  }

  @Override
  public synchronized void disable() {
    disabled = true;
  }

  private void addIfEmtpy() {
    if (stack.isEmpty()) {
      stack.add(new UndoState());
    }
  }

  @Override
  public synchronized int size() {
    return stack.size();
  }

  public void setMaxSize(int maxSize) {
    this.maxSize.set(maxSize);
  }

  public int getMaxSize() {
    return maxSize.get();
  }

  public synchronized void shutdown() {
    System.err.println("******** begin to pop revokingDb ********");
    System.err.println("******** before revokingDb size:" + size());
    try {
      disable();
      boolean exit = false;
      while (!exit) {
        try {
          commit();
        } catch (RevokingStoreIllegalStateException e) {
          exit = true;
        }
      }

      while (true) {
        try {
          pop();
        } catch (RevokingStoreIllegalStateException e) {
          break;
        }
      }
    } catch (Exception e) {
      System.err.println("******** faild to pop revokingStore. " + e);
    } finally {
      System.err.println("******** after revokingStore size:" + stack.size());
      System.err.println("******** after revokingStore contains:" + stack);
      System.err.println("******** end to pop revokingStore ********");
    }
  }

  @Slf4j
  @Getter // only for unit test
  public static class Dialog implements AutoCloseable {

    private IUndoStore revokingDatabase;
    private boolean applyUndo = true;
    private boolean disableOnExit = false;

    public Dialog(Dialog dialog) {
      this.revokingDatabase = dialog.revokingDatabase;
      this.applyUndo = dialog.applyUndo;
      dialog.applyUndo = false;
    }

    public Dialog(IUndoStore revokingDatabase) {
      this(revokingDatabase, false);
    }

    public Dialog(IUndoStore revokingDatabase, boolean disbaleOnExit) {
      this.revokingDatabase = revokingDatabase;
      this.disableOnExit = disbaleOnExit;
    }

    void commit() throws RevokingStoreIllegalStateException {
      applyUndo = false;
      revokingDatabase.commit();
    }

    void revoke() throws RevokingStoreIllegalStateException {
      if (applyUndo) {
        revokingDatabase.revoke();
      }

      applyUndo = false;
    }

    void merge() throws RevokingStoreIllegalStateException {
      if (applyUndo) {
        revokingDatabase.merge();
      }

      applyUndo = false;
    }

    void copy(Dialog dialog) throws RevokingStoreIllegalStateException {
      if (this.equals(dialog)) {
        return;
      }

      if (applyUndo) {
        revokingDatabase.revoke();
      }
      applyUndo = dialog.applyUndo;
      dialog.applyUndo = false;
    }

    public void destroy() {
      try {
        if (applyUndo) {
          revokingDatabase.revoke();
        }
      } catch (Exception e) {
        logger.error("revoke database error.", e);
      }
      if (disableOnExit) {
        revokingDatabase.disable();
      }
    }

    @Override
    public void close() throws RevokingStoreIllegalStateException {
      try {
        if (applyUndo) {
          revokingDatabase.revoke();
        }
      } catch (Exception e) {
        logger.error("revoke database error.", e);
        throw new RevokingStoreIllegalStateException(e);
      }
      if (disableOnExit) {
        revokingDatabase.disable();
      }
    }
  }

  @ToString
  @Getter // only for unit test
  static class UndoState {

    Map<UndoTuple, byte[]> oldValues = new HashMap<>();
    Set<UndoTuple> newIds = new HashSet<>();
    Map<UndoTuple, byte[]> removed = new HashMap<>();
  }

  @AllArgsConstructor
  @EqualsAndHashCode
  @Getter
  @ToString
  public static class UndoTuple {

    private SourceInter<byte[], byte[]> database;
    private byte[] key;
  }

}
