package network.awai.drive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** App-private organization operations, serialized by DrivePlugin's single writer. */
final class DriveOrganization {
    private final DriveFiles local;
    private final File trashRoot;

    DriveOrganization(DriveFiles local, File filesDir) {
        this.local = local;
        this.trashRoot = new File(filesDir, "drive-trash-v1");
    }

    private File root() throws Exception {
        if (Files.isSymbolicLink(trashRoot.toPath())) throw new IOException("invalid-path");
        if (!trashRoot.exists()) {
            Files.createDirectory(trashRoot.toPath());
            DriveVault.sync(trashRoot.getParentFile());
        }
        if (!trashRoot.isDirectory()) throw new IOException("invalid-path");
        return trashRoot;
    }

    private File slot(String id) throws Exception {
        if (!id.matches("[a-f0-9-]{1,64}")) throw new IOException("invalid-path");
        File slot = new File(root(), id);
        for (File file : new File[]{slot, new File(slot, "original"), new File(slot, "item")}) {
            if (Files.isSymbolicLink(file.toPath())) throw new IOException("invalid-path");
        }
        if (!slot.isDirectory()) throw new IOException("invalid-path");
        return slot;
    }

    private static String read(File file, int limit) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
            byte[] buffer = new byte[Math.min(limit, 8192)];
            while (output.size() < limit) {
                int n = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
                if (n < 0) break;
                output.write(buffer, 0, n);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String original(File slot) throws IOException {
        String path = read(new File(slot, "original"), 8193);
        if (path.isEmpty() || path.length() > 2048) throw new IOException("invalid-path");
        return path;
    }

    private static void relocate(File from, File to) throws Exception {
        // All writable local adapters run on the same worker. Exported providers are read-only.
        if (Files.exists(to.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException("file-exists");
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
        DriveVault.sync(from.getParentFile());
        if (!from.getParentFile().equals(to.getParentFile())) DriveVault.sync(to.getParentFile());
    }

    private File destination(String parent, String name) throws IOException {
        if (!local.resolve(parent).isDirectory()) throw new IOException("invalid-path");
        return local.resolve(parent.isEmpty() ? DriveFiles.name(name) : parent + "/" + DriveFiles.name(name));
    }

    String move(String path, String parent, String name) throws Exception {
        if (path.isEmpty()) throw new IOException("invalid-path");
        File from = local.resolve(path);
        if (!from.exists()) throw new IOException("invalid-path");
        File to = destination(parent, name);
        if (to.getParentFile().toPath().startsWith(from.toPath())) throw new IOException("invalid-path");
        if (!from.equals(to)) {
            if (!from.getParentFile().equals(to.getParentFile()) && local.list(parent).length >= DriveFiles.MAX_ENTRIES) {
                throw new IOException("entry-limit");
            }
            relocate(from, to);
        }
        return local.path(to);
    }

    String trash(String path) throws Exception {
        if (path.isEmpty()) throw new IOException("invalid-path");
        File from = local.resolve(path);
        File root = root();
        String[] slots = root.list();
        if (slots == null || slots.length >= DriveFiles.MAX_ENTRIES) throw new IOException("entry-limit");
        String id = UUID.randomUUID().toString();
        File slot = new File(root, id);
        Files.createDirectory(slot.toPath());
        File record = new File(slot, "original");
        File item = new File(slot, "item");
        try {
            try (FileOutputStream out = new FileOutputStream(record)) {
                out.write(local.path(from).getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            DriveVault.sync(slot);
            DriveVault.sync(root);
            relocate(from, item);
        } catch (Exception failure) {
            if (!item.exists()) { record.delete(); slot.delete(); }
            throw failure;
        }
        return id;
    }

    JSONArray list() throws Exception {
        JSONArray rows = new JSONArray();
        String[] ids = root().list();
        if (ids == null || ids.length > DriveFiles.MAX_ENTRIES) throw new IOException("entry-limit");
        for (String id : ids) {
            File slot = slot(id);
            File item = new File(slot, "item");
            if (!item.exists()) continue;
            String path = original(slot);
            rows.put(new JSONObject().put("path", id).put("name", path.substring(path.lastIndexOf('/') + 1))
                .put("directory", item.isDirectory()).put("bytes", item.isDirectory() ? 0 : item.length())
                .put("modified", item.lastModified()));
        }
        return rows;
    }

    void recover(String id) throws Exception {
        File slot = slot(id);
        String path = original(slot);
        int slash = path.lastIndexOf('/');
        String parent = slash < 0 ? "" : path.substring(0, slash);
        if (local.list(parent).length >= DriveFiles.MAX_ENTRIES) throw new IOException("entry-limit");
        relocate(new File(slot, "item"), destination(parent, path.substring(slash + 1)));
        new File(slot, "original").delete();
        slot.delete();
    }

    String preview(String path) throws IOException {
        File file = local.resolve(path);
        if (!file.isFile()) throw new IOException("invalid-path");
        return read(file, 65536);
    }
}
