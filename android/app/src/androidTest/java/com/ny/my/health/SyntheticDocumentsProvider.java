package com.ny.my.health;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ResultReceiver;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
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
        PARTIAL, LOADING, NULL_QUERY, NO_TRUNCATE, WRONG_RETURN_IDENTITY,
        CREATE_AFTER_EFFECT_FAILURE, WRITE_INTERRUPTION, CLOSE_INTERRUPTION,
        BLOCK_BEFORE_EFFECT, RELEASE_BLOCK
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
    public static final String EXTRA_CONTROL = "synthetic_control";
    public static final String EXTRA_RECEIVER = "synthetic_receiver";
    public static final String EXTRA_UID = "synthetic_uid";
    public static final int ACCESS_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private static final Object lock = new Object();
    private static final Map<String, Node> nodes = new LinkedHashMap<>();
    private static File storage;
    private static Control outcome;
    private static volatile CountDownLatch barrier;

    public static byte[] seededBytes() {
        return "synthetic fixture only\n".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean onCreate() {
        synchronized (lock) {
            storage = new File(Objects.requireNonNull(getContext()).getNoBackupFilesDir(),
                    "synthetic-saf-fixture");
            try {
                reset();
            } catch (FileNotFoundException ignored) {
                throw new IllegalStateException("합성 저장소 준비 실패");
            }
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
            return cursor(Collections.singletonList(node(documentId)), projection);
        }
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        synchronized (lock) {
            node(parentDocumentId);
            if (outcome == Control.NULL_QUERY) return null;
            List<Node> children = new ArrayList<>();
            for (Node child : nodes.values()) {
                if (Objects.equals(child.parent, parentDocumentId)) {
                    children.add(child);
                    if (outcome == Control.PARTIAL) break;
                }
            }
            MatrixCursor cursor = cursor(children, projection);
            if (outcome == Control.LOADING) {
                Bundle extras = new Bundle();
                extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
                cursor.setExtras(extras);
            }
            return cursor;
        }
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        synchronized (lock) {
            if (!nodes.containsKey(parentDocumentId)) return false;
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
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    /** 외부 입력은 고정 enum뿐이며 grant 수신자와 tree도 고정한다. */
    public static final class GrantActivity extends Activity {
        @Override
        @SuppressWarnings("deprecation")
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            ResultReceiver receiver = getIntent().getParcelableExtra(EXTRA_RECEIVER);
            boolean success = false;
            try {
                Control command = null;
                String requested = getIntent().getStringExtra(EXTRA_CONTROL);
                for (Control candidate : Control.values()) {
                    if (candidate.name().equals(requested)) command = candidate;
                }
                if (command == null) throw new IllegalArgumentException("합성 제어 거부");
                Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, TREE_ID);
                if (command == Control.GRANT_READ_WRITE || command == Control.GRANT_READ_ONLY) {
                    int access = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | (command == Control.GRANT_READ_WRITE ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
                    grantUriPermission(TARGET_PACKAGE, tree, access
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                } else if (command == Control.REVOKE) {
                    revokeUriPermission(TARGET_PACKAGE, tree, ACCESS_FLAGS);
                } else {
                    control(command);
                }
                success = true;
            } catch (Exception ignored) {
                // 원시 provider 예외와 URI는 callback·공개 로그로 전달하지 않는다.
            } finally {
                if (receiver != null) {
                    Bundle result = new Bundle();
                    result.putInt(EXTRA_UID, Process.myUid());
                    receiver.send(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
                }
                finish();
            }
        }
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
        String id = UUID.randomUUID().toString();
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
        if (barrier != null) barrier.countDown();
        barrier = null;
        outcome = null;
        deleteRecursively(storage);
        if (!storage.mkdirs()) throw new IllegalStateException("합성 저장소 준비 실패");
        nodes.clear();
        nodes.put(TREE_ID, new Node(TREE_ID, null, Document.MIME_TYPE_DIR, "합성 폴더", null));
        nodes.put(OUTSIDE_ID, new Node(OUTSIDE_ID, null, Document.MIME_TYPE_DIR, "합성 외부", null));
        nodes.put(NESTED_ID, new Node(NESTED_ID, TREE_ID, Document.MIME_TYPE_DIR, "합성 하위", null));
        File file = new File(storage, "seed");
        writeBytes(file, seededBytes());
        nodes.put(SEEDED_ID, new Node(SEEDED_ID, NESTED_ID, "text/plain", "seed.txt", file));
    }

    private static void control(Control command) throws FileNotFoundException {
        synchronized (lock) {
            if (command == Control.RESET) reset();
            else if (command == Control.DUPLICATE)
                writeBytes(addNode(NESTED_ID, "text/plain", "seed.txt").file, seededBytes());
            else if (command == Control.MOVE_OUTSIDE_TREE) node(SEEDED_ID).parent = OUTSIDE_ID;
            else if (command == Control.DELETE_SEEDED) {
                Node removed = nodes.remove(SEEDED_ID);
                if (removed != null && removed.file != null) removed.file.delete();
            } else if (command == Control.BLOCK_BEFORE_EFFECT) barrier = new CountDownLatch(1);
            else if (command == Control.RELEASE_BLOCK) {
                if (barrier != null) barrier.countDown();
                barrier = null;
            } else outcome = command;
        }
    }

    private static void checkpoint() throws FileNotFoundException {
        CountDownLatch current = barrier;
        if (current == null) return;
        try {
            if (!current.await(10, TimeUnit.SECONDS)) throw syntheticFailure();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw syntheticFailure();
        }
    }
}
