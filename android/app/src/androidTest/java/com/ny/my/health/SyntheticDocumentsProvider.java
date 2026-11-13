package com.ny.my.health;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Log;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 테스트 APK UID의 전용 합성 저장소. 별도 프로세스에서 Kotlin 런타임을 요구하지 않는다. */
public final class SyntheticDocumentsProvider extends DocumentsProvider {
    public enum Control {
        RESET, GRANT_READ_WRITE, GRANT_READ_ONLY, REVOKE,
        DUPLICATE, MOVE_OUTSIDE_TREE, DELETE_SEEDED,
        PARTIAL, DOCUMENT_PARTIAL, SECURITY_FAILURE, SAME_TARGET_ACROSS_DATASETS, OVERSIZED_TARGET_ID, LOADING, NULL_QUERY, NO_TRUNCATE, WRONG_RETURN_IDENTITY,
        CREATE_AFTER_EFFECT_FAILURE, WRITE_INTERRUPTION, CLOSE_INTERRUPTION,
        BLOCK_BEFORE_EFFECT, RELEASE_BLOCK, OBSERVE, OPAQUE_IDS,
        INCOMPLETE, NULL_DESCRIPTOR, SHORT_WRITE, CLEANUP_FAILURE, READBACK_FAILURE
    }

    public enum Stage { DISPATCH, CREATE, NEW_INTENT, EFFECT, SEND, RECEIVE, FINISH }

    /** 로그 입력은 합성 enum과 단조 시간뿐이다. 외부 문자열·결과·식별자는 받지 않는다. */
    public static void safeLog(Control command, Stage stage) {
        Log.i("SyntheticSafControl", (command == null ? "INVALID" : command.name())
                + " " + stage.name() + " " + SystemClock.elapsedRealtime());
    }

    private static final class Node {
        final String id;
        String parent;
        final String mime;
        final String name;
        final File file;

        Node(String id, String parent, String mime, String name, File file) {
            this.id = id;
            this.parent = parent;
            this.mime = mime;
            this.name = name;
            this.file = file;
        }
    }

    public static final String AUTHORITY = "com.ny.my.health.test.synthetic.documents";
    public static final String TARGET_PACKAGE = "com.ny.my.health";
    public static final String TREE_ID = "opaque-tree-a";
    public static final String SEEDED_ID = "opaque-document-b";
    private static final String OUTSIDE_ID = "opaque-directory-c";
    private static final String NESTED_ID = "opaque-directory-d";
    public static final String EXTRA_TARGET_URI_BYTES = "synthetic_target_uri_bytes";
    public static final String EXTRA_TARGET_ID = "synthetic_target_id";
    public static final String EXTRA_CONTROL = "synthetic_control";
    public static final String EXTRA_RECEIVER = "synthetic_receiver";
    public static final String EXTRA_UID = "synthetic_uid";
    public static final String EXTRA_COUNTS = "synthetic_counts";
    public static final String EXTRA_RESULT_DATA = "synthetic_result_data";
    public static final String EXTRA_BLOCK_RECEIVER = "synthetic_block_receiver";
    public static final String OPAQUE_SPACE_ID = "opaque space document";
    public static final String OPAQUE_UNICODE_ID = "합성 문서 Ω";
    public static final String OPAQUE_RESERVED_ID = "opaque:/?#%[]@!$&'()+,;=";
    public static final String NESTED_DOCUMENT_ID = NESTED_ID;
    public static final String COUNT_QUERY = "query";
    public static final String COUNT_CHILDREN = "children";
    public static final String COUNT_CREATE = "create";
    public static final String COUNT_READ = "read";
    public static final String COUNT_WRITE = "write";
    public static final String COUNT_DELETE = "delete";
    public static final String COUNT_MOVE = "move";
    public static final int SHORT_WRITE_CAPACITY = 4;
    public static final int ACCESS_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private static final Object lock = new Object();
    private static final Map<String, Node> nodes = new LinkedHashMap<>();
    private static File storage;
    private static Control outcome;
    private static int targetUriBytes = 4097;
    private static final Bundle counts = new Bundle();
    private static HandlerThread descriptorThread;

    /** 별도 프로세스 관찰은 OBSERVE callback의 합성 횟수만 사용한다. */
    private static void count(String key) {
        counts.putInt(key, counts.getInt(key, 0) + 1);
    }

    public static int queryCount(Bundle snapshot) { return snapshot.getInt(COUNT_QUERY, 0); }
    public static int childQueryCount(Bundle snapshot) { return snapshot.getInt(COUNT_CHILDREN, 0); }
    public static int createCount(Bundle snapshot) { return snapshot.getInt(COUNT_CREATE, 0); }
    public static int readCount(Bundle snapshot) { return snapshot.getInt(COUNT_READ, 0); }
    public static int writeCount(Bundle snapshot) { return snapshot.getInt(COUNT_WRITE, 0); }
    public static int deleteCount(Bundle snapshot) { return snapshot.getInt(COUNT_DELETE, 0); }
    public static int moveCount(Bundle snapshot) { return snapshot.getInt(COUNT_MOVE, 0); }
    private static volatile CountDownLatch barrier;
    private static volatile ResultReceiver blockReceiver;

    public static byte[] seededBytes() {
        return "synthetic fixture only\n".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean onCreate() {
        synchronized (lock) {
            storage = new File(Objects.requireNonNull(getContext()).getNoBackupFilesDir(),
                    "synthetic-saf-fixture");
        }
        try {
            reset();
        } catch (FileNotFoundException ignored) {
            throw new IllegalStateException("합성 저장소 준비 실패");
        }
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        synchronized (lock) {
            String[] columns = projection != null ? projection : new String[] {
                    Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS};
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                String column = columns[i];
                if (Root.COLUMN_ROOT_ID.equals(column)) row[i] = "synthetic-root";
                else if (Root.COLUMN_DOCUMENT_ID.equals(column)) row[i] = TREE_ID;
                else if (Root.COLUMN_TITLE.equals(column)) row[i] = "합성 테스트";
                else if (Root.COLUMN_FLAGS.equals(column))
                    row[i] = Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD;
            }
            cursor.addRow(row);
            return cursor;
        }
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        synchronized (lock) {
            if (outcome == Control.SECURITY_FAILURE) throw new SecurityException("합성 접근 거부");
            count(COUNT_QUERY);
            return cursor(Collections.singletonList(node(documentId)), projection);
        }
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        synchronized (lock) {
            node(parentDocumentId);
            if (outcome == Control.SECURITY_FAILURE) throw new SecurityException("합성 접근 거부");
            count(COUNT_CHILDREN);
            if (outcome == Control.NULL_QUERY) return null;
            List<Node> children = new ArrayList<>();
            for (Node child : nodes.values()) {
                if (Objects.equals(child.parent, parentDocumentId)) {
                    children.add(child);
                    if (outcome == Control.PARTIAL || outcome == Control.INCOMPLETE) break;
                }
            }
            if (outcome == Control.SAME_TARGET_ACROSS_DATASETS && "workouts".equals(node(parentDocumentId).name)) {
                Node alias = canonicalDaily();
                if (alias == null) throw syntheticFailure();
                children.clear();
                children.add(alias);
            }
            MatrixCursor cursor = cursor(children, projection);
            if (outcome == Control.LOADING || outcome == Control.INCOMPLETE) {
                Bundle extras = new Bundle();
                extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                if (outcome == Control.INCOMPLETE)
                    extras.putString(DocumentsContract.EXTRA_ERROR, "합성 목록 불완전");
                cursor.setExtras(extras);
            }
            return cursor;
        }
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        synchronized (lock) {
            if (!nodes.containsKey(parentDocumentId)) return false;
            if (outcome == Control.SAME_TARGET_ACROSS_DATASETS && "workouts".equals(nodes.get(parentDocumentId).name)
                    && canonicalDaily() != null && canonicalDaily().id.equals(documentId)) return true;
            Node child = nodes.get(documentId);
            String current = child == null ? null : child.parent;
            Set<String> seen = new HashSet<>();
            while (current != null && seen.add(current)) {
                if (current.equals(parentDocumentId)) return true;
                Node parent = nodes.get(current);
                current = parent == null ? null : parent.parent;
            }
            return false;
        }
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        checkpoint();
        synchronized (lock) {
            count(COUNT_CREATE);
            if (!Document.MIME_TYPE_DIR.equals(node(parentDocumentId).mime)) throw syntheticFailure();
            Node created = addNode(parentDocumentId, mimeType, displayName);
            if (outcome == Control.CREATE_AFTER_EFFECT_FAILURE) throw syntheticFailure();
            return outcome == Control.WRONG_RETURN_IDENTITY ? OUTSIDE_ID : created.id;
        }
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        checkpoint();
        synchronized (lock) {
            File file = node(documentId).file;
            if (file == null) throw syntheticFailure();
            boolean writable = mode.indexOf('w') >= 0;
            count(writable ? COUNT_WRITE : COUNT_READ);
            if (outcome == Control.NULL_DESCRIPTOR) return null;
            if (!writable && outcome == Control.READBACK_FAILURE) throw syntheticFailure();
            if (writable && outcome == Control.SHORT_WRITE) {
                try {
                    if (descriptorThread == null) {
                        descriptorThread = new HandlerThread("synthetic-descriptor");
                        descriptorThread.start();
                    }
                    return Objects.requireNonNull(getContext()).getSystemService(StorageManager.class)
                            .openProxyFileDescriptor(ParcelFileDescriptor.MODE_WRITE_ONLY,
                                    new ShortWriteCallback(file), new Handler(descriptorThread.getLooper()));
                } catch (IOException ignored) {
                    throw syntheticFailure();
                }
            }
            if (writable && outcome == Control.WRITE_INTERRUPTION) throw syntheticFailure();
            if (writable && outcome == Control.CLOSE_INTERRUPTION) {
                // reliable descriptor의 오류는 checkError로 관찰한다. close() 예외를 가정하지 않는다.
                try {
                    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                    pipe[0].closeWithError("합성 종료 실패");
                    return pipe[1];
                } catch (IOException ignored) {
                    throw syntheticFailure();
                }
            }
            int flags = ParcelFileDescriptor.parseMode(mode);
            if (writable && outcome == Control.NO_TRUNCATE)
                flags &= ~ParcelFileDescriptor.MODE_TRUNCATE;
            return ParcelFileDescriptor.open(file, flags);
        }
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        synchronized (lock) {
            count(COUNT_DELETE);
            if (outcome == Control.CLEANUP_FAILURE) throw syntheticFailure();
            if (TREE_ID.equals(documentId) || OUTSIDE_ID.equals(documentId)) throw syntheticFailure();
            Node removed = node(documentId);
            if (removed.file != null) removed.file.delete();
            nodes.remove(documentId);
        }
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId,
            String targetParentDocumentId) throws FileNotFoundException {
        synchronized (lock) {
            count(COUNT_MOVE);
            Node source = node(sourceDocumentId);
            if (!Objects.equals(source.parent, sourceParentDocumentId) || source.file == null
                    || !Document.MIME_TYPE_DIR.equals(node(targetParentDocumentId).mime))
                throw syntheticFailure();
            source.parent = targetParentDocumentId;
            return source.id;
        }
    }

    private static MatrixCursor cursor(List<Node> values, String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        for (Node entry : values) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                String column = columns[i];
                if (Document.COLUMN_DOCUMENT_ID.equals(column)) row[i] = entry.id;
                else if (Document.COLUMN_DISPLAY_NAME.equals(column)) row[i] = entry.name;
                else if (Document.COLUMN_MIME_TYPE.equals(column)) row[i] = entry.mime;
                else if (Document.COLUMN_SIZE.equals(column)) row[i] = entry.file == null ? 0L : entry.file.length();
                else if (Document.COLUMN_FLAGS.equals(column))
                    row[i] = Document.MIME_TYPE_DIR.equals(entry.mime) ? Document.FLAG_DIR_SUPPORTS_CREATE
                            : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_MOVE;
                if (Document.COLUMN_FLAGS.equals(column) && outcome == Control.DOCUMENT_PARTIAL)
                    row[i] = ((Integer) row[i]) | Document.FLAG_PARTIAL;
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    /** 외부 입력은 고정 enum뿐이며 grant 수신자와 tree도 고정한다. */
    public static final class GrantActivity extends Activity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            handleIntent(getIntent(), Stage.CREATE);
        }

        @Override
        protected void onNewIntent(Intent intent) {
            super.onNewIntent(intent);
            setIntent(intent);
            handleIntent(intent, Stage.NEW_INTENT);
        }

        @SuppressWarnings("deprecation")
        private void handleIntent(Intent intent, Stage stage) {
            ResultReceiver receiver = null;
            Control command = null;
            boolean success = false;
            Intent activityResult = null;
            try {
                receiver = intent.getParcelableExtra(EXTRA_RECEIVER);
                String requested = intent.getStringExtra(EXTRA_CONTROL);
                for (Control candidate : Control.values()) {
                    if (candidate.name().equals(requested)) command = candidate;
                }
                safeLog(command, stage);
                if (command == null) throw new IllegalArgumentException("합성 제어 거부");
                if (command != Control.OVERSIZED_TARGET_ID && intent.hasExtra(EXTRA_TARGET_URI_BYTES))
                    throw new IllegalArgumentException("합성 길이 제어 거부");
                Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, TREE_ID);
                if (command == Control.GRANT_READ_WRITE || command == Control.GRANT_READ_ONLY) {
                    int access = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | (command == Control.GRANT_READ_WRITE ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
                    int grantedFlags = access | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
                    grantUriPermission(TARGET_PACKAGE, tree, grantedFlags);
                    activityResult = new Intent().setData(tree).addFlags(grantedFlags);
                } else if (command == Control.REVOKE) {
                    revokeUriPermission(TARGET_PACKAGE, tree, ACCESS_FLAGS);
                } else {
                    if (command == Control.BLOCK_BEFORE_EFFECT)
                        blockReceiver = intent.getParcelableExtra(EXTRA_BLOCK_RECEIVER);
                    int size = 4097;
                    if (command == Control.OVERSIZED_TARGET_ID) {
                        size = intent.getIntExtra(EXTRA_TARGET_URI_BYTES, 4097);
                        if (size < 4096 || size > 4097) throw new IllegalArgumentException("합성 길이 거부");
                    } else if (intent.hasExtra(EXTRA_TARGET_URI_BYTES)) throw new IllegalArgumentException("합성 길이 제어 거부");
                    control(command, intent.getStringExtra(EXTRA_TARGET_ID), size);
                }
                success = true;
                safeLog(command, Stage.EFFECT);
            } catch (Exception ignored) {
                // 원시 provider 예외와 URI는 callback·공개 로그로 전달하지 않는다.
            } finally {
                try {
                    if (receiver != null) {
                        Bundle result = new Bundle();
                        result.putInt(EXTRA_UID, Process.myUid());
                        if (command == Control.OBSERVE) {
                            synchronized (lock) { result.putBundle(EXTRA_COUNTS, new Bundle(counts)); }
                        }
                        // 비공개 테스트 callback에는 실제 ActivityResult와 같은 원본 data를 전달한다.
                        if (activityResult != null) result.putParcelable(EXTRA_RESULT_DATA, activityResult);
                        safeLog(command, Stage.SEND);
                        receiver.send(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
                    }
                } finally {
                    setResult(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, activityResult);
                    safeLog(command, Stage.FINISH);
                    finish();
                }
            }
        }
    }

    /** 실제 write가 고정 용량만 수용한 후 ENOSPC를 반환하는 합성 descriptor. */
    private static final class ShortWriteCallback extends ProxyFileDescriptorCallback {
        private final File file;
        private int written;

        ShortWriteCallback(File file) throws FileNotFoundException {
            this.file = file;
            writeBytes(file, new byte[0]);
        }

        @Override public long onGetSize() { return written; }
        @Override public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
            int accepted = Math.min(size, SHORT_WRITE_CAPACITY - written);
            if (accepted <= 0) throw new ErrnoException("합성 짧은 쓰기", OsConstants.ENOSPC);
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write(data, 0, accepted);
                written += accepted;
                return accepted;
            } catch (IOException ignored) {
                throw new ErrnoException("합성 짧은 쓰기", OsConstants.EIO);
            }
        }
        @Override public void onFsync() { }
        @Override public void onRelease() { }
    }

    private static Node node(String id) throws FileNotFoundException {
        Node value = nodes.get(id);
        if (value == null) throw syntheticFailure();
        return value;
    }

    private static FileNotFoundException syntheticFailure() {
        return new FileNotFoundException("합성 작업 실패");
    }

    private static void writeBytes(File file, byte[] bytes) throws FileNotFoundException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        } catch (IOException ignored) {
            throw syntheticFailure();
        }
    }

    private static Node addNode(String parent, String mime, String name) throws FileNotFoundException {
        // 표시 이름·document ID를 파일 경로로 해석하지 않는다.
        String id = outcome == Control.OVERSIZED_TARGET_ID && "text/csv".equals(mime)
                ? sizedId(targetUriBytes) : UUID.randomUUID().toString();
        File file = Document.MIME_TYPE_DIR.equals(mime) ? null : new File(storage, UUID.randomUUID().toString());
        if (file != null) writeBytes(file, new byte[0]);
        Node added = new Node(id, parent, mime, name, file);
        nodes.put(id, added);
        return added;
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static void reset() throws FileNotFoundException {
        HandlerThread previous;
        synchronized (lock) {
            previous = descriptorThread;
            descriptorThread = null;
        }
        // callback 종료 대기는 provider lock 밖에서만 수행한다.
        if (previous != null) {
            previous.quitSafely();
            try {
                previous.join(1000);
                if (previous.isAlive()) throw syntheticFailure();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                throw syntheticFailure();
            }
        }
        synchronized (lock) {
            if (barrier != null) barrier.countDown();
            barrier = null;
            blockReceiver = null;
            outcome = null;
            deleteRecursively(storage);
            if (!storage.mkdirs()) throw new IllegalStateException("합성 저장소 준비 실패");
            nodes.clear();
            counts.clear();
            nodes.put(TREE_ID, new Node(TREE_ID, null, Document.MIME_TYPE_DIR, "합성 폴더", null));
            nodes.put(OUTSIDE_ID, new Node(OUTSIDE_ID, null, Document.MIME_TYPE_DIR, "합성 외부", null));
            nodes.put(NESTED_ID, new Node(NESTED_ID, TREE_ID, Document.MIME_TYPE_DIR, "합성 하위", null));
            File file = new File(storage, "seed");
            writeBytes(file, seededBytes());
            nodes.put(SEEDED_ID, new Node(SEEDED_ID, NESTED_ID, "text/plain", "seed.txt", file));
        }
    }

    private static void control(Control command, String targetId, int size) throws FileNotFoundException {
        if (command == Control.RESET) {
            reset();
            return;
        }
        synchronized (lock) {
            if (command == Control.OBSERVE) { /* 결과는 고정 횟수만 반환한다. */ }
            else if (command == Control.OVERSIZED_TARGET_ID) {
                targetUriBytes = size;
                Node selected = canonicalDaily();
                if (selected != null) {
                    nodes.remove(selected.id);
                    String id = sizedId(size);
                    nodes.put(id, new Node(id, selected.parent, selected.mime, selected.name, selected.file));
                }
                outcome = command;
            }
            else if (command == Control.OPAQUE_IDS) {
                String[] ids = { OPAQUE_SPACE_ID, OPAQUE_UNICODE_ID, OPAQUE_RESERVED_ID };
                for (int i = 0; i < ids.length; i++) {
                    File file = new File(storage, "opaque-" + i);
                    writeBytes(file, seededBytes());
                    nodes.put(ids[i], new Node(ids[i], NESTED_ID, "text/plain", "opaque-" + i, file));
                }
            }
            else if (command == Control.DUPLICATE) {
                Node selected = node(targetId == null ? SEEDED_ID : targetId);
                Node duplicate = addNode(selected.parent, selected.mime, selected.name);
                if (duplicate.file != null) writeBytes(duplicate.file, seededBytes());
            }
            else if (command == Control.MOVE_OUTSIDE_TREE) node(targetId == null ? SEEDED_ID : targetId).parent = OUTSIDE_ID;
            else if (command == Control.DELETE_SEEDED) {
                Node removed = nodes.remove(targetId == null ? SEEDED_ID : targetId);
                if (removed != null && removed.file != null) removed.file.delete();
            } else if (command == Control.BLOCK_BEFORE_EFFECT) barrier = new CountDownLatch(1);
            else if (command == Control.RELEASE_BLOCK) {
                if (barrier != null) barrier.countDown();
                barrier = null;
                blockReceiver = null;
            } else outcome = command;
        }
    }

    /** 고정 daily 합성 CSV만 선택한다. 외부 경로나 식별자를 해석하지 않는다. */
    private static Node canonicalDaily() {
        for (Node value : nodes.values()) {
            Node parent = nodes.get(value.parent);
            if (parent != null && "daily".equals(parent.name) && "text/csv".equals(value.mime)) return value;
        }
        return null;
    }

    private static String sizedId(int bytes) {
        Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, TREE_ID);
        int overhead = DocumentsContract.buildDocumentUriUsingTree(tree, "a").toString()
                .getBytes(StandardCharsets.UTF_8).length - 1;
        StringBuilder value = new StringBuilder();
        for (int i = overhead; i < bytes; i++) value.append('a');
        return value.toString();
    }

    private static void checkpoint() throws FileNotFoundException {
        CountDownLatch current = barrier;
        if (current == null) return;
        // 비공개 callback에는 고정 진입 결과와 발급자 UID만 전달한다.
        ResultReceiver entered = blockReceiver;
        if (entered != null) {
            Bundle event = new Bundle();
            event.putInt(EXTRA_UID, Process.myUid());
            entered.send(Activity.RESULT_OK, event);
        }
        try {
            if (!current.await(10, TimeUnit.SECONDS)) throw syntheticFailure();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw syntheticFailure();
        }
    }
}
