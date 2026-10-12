package com.bimba.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.stream.Stream;

/** File helpers: private files (0600 / 0700) written atomically, ownership for the service account. */
final class Files2 {
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private Files2() {
    }

    /** creates the folder (and parents) and limits it to its owner */
    static void privateDir(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (POSIX) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /** write to a temporary file readable only by the owner, then move it into place */
    static void writePrivate(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        if (POSIX) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.write(tmp, content);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void writePrivate(Path file, String content) throws IOException {
        writePrivate(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** chown -R user:user (the service account); false when the account does not exist or this is not POSIX */
    static boolean chownTree(Path root, String user) {
        if (!POSIX || !Files.exists(root)) {
            return false;
        }
        try {
            UserPrincipalLookupService lookup = FileSystems.getDefault().getUserPrincipalLookupService();
            UserPrincipal owner = lookup.lookupPrincipalByName(user);
            GroupPrincipal group = lookup.lookupPrincipalByGroupName(user);
            try (Stream<Path> all = Files.walk(root)) {
                for (Path p : (Iterable<Path>) all::iterator) {
                    PosixFileAttributeView v = Files.getFileAttributeView(p, PosixFileAttributeView.class);
                    v.setOwner(owner);
                    v.setGroup(group);
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> all = Files.walk(root)) {
            for (Path p : all.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
