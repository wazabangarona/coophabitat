package sn.coophabitat.document;

import org.springframework.stereotype.Component;
import sn.coophabitat.config.AppProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Stockage des fichiers hors base, sur un répertoire local (volume Docker en déploiement).
 * Les clés sont générées côté serveur : aucun nom fourni par l'utilisateur n'entre dans un chemin.
 * Point d'extension : remplacer par un stockage objet chiffré pour une exploitation réelle.
 */
@Component
public class FileStorage {

    private final Path root;

    public FileStorage(AppProperties props) {
        this.root = Path.of(props.storage().path()).toAbsolutePath().normalize();
    }

    public String store(byte[] content) {
        LocalDate d = LocalDate.now();
        String key = d.getYear() + "/" + String.format("%02d", d.getMonthValue()) + "/" + UUID.randomUUID();
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".upload", ".tmp");
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Écriture du fichier impossible", e);
        }
        return key;
    }

    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture du fichier impossible", e);
        }
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Clé de stockage invalide");
        }
        return p;
    }
}
