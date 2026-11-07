# Aventum Scan — scanner de documents Android

Application Android très simple :

1. **Choisir le format** des fichiers (le choix est mémorisé) :
   - **JPG** – un fichier par page ;
   - **PDF** – un fichier par page ;
   - **PDF** – un seul fichier pour toutes les pages (scan en lot) ;
   - **Texte (OCR) – TXT** : le texte reconnu, toutes les pages dans un fichier ;
   - **Texte (OCR) – Word (DOCX)** : le texte reconnu, un saut de page entre chaque page.
2. **Scanner** un document avec l'appareil photo (détection automatique des bords,
   recadrage, filtres noir & blanc, plusieurs pages, import depuis la galerie possible).
3. Choisir l'action :
   - **Enregistrer** : les JPG vont dans la galerie, album **Scans** (`Images/Scans`) ;
     les PDF, TXT et DOCX dans **Téléchargements/Scans** ;
   - **E-mail** : ouvre directement une application de messagerie (Gmail, Outlook…) avec les fichiers en pièce jointe ;
   - **Partager** : toutes les applications installées (Messages, WhatsApp, Drive, Teams…).

La reconnaissance de texte (OCR) se fait sur le téléphone : les documents ne sont pas envoyés sur internet.
Le scan s'appuie sur le scanner de documents Google (ML Kit), fourni par les services Google Play
— aucune autorisation « appareil photo » n'est donc nécessaire.

## Récupérer l'APK

À chaque push, GitHub Actions compile l'application :

1. Onglet **Actions** du dépôt → workflow **Construire l'APK** → dernière exécution.
2. Télécharger l'artefact **AventumScan-apk** (fichier zip contenant `app-release.apk`).
3. Copier l'APK sur le téléphone, l'ouvrir et autoriser « l'installation d'applications inconnues ».

## Compiler en local

Avec Android Studio (ouvrir le dossier) ou en ligne de commande avec le SDK Android installé :

```bash
./gradlew assembleRelease
# APK : app/build/outputs/apk/release/app-release.apk
```

## Configuration requise

- Android 8.0 (API 26) ou plus récent
- Services Google Play (présents sur la quasi-totalité des téléphones Android)

## Structure

| Fichier | Rôle |
|---|---|
| `app/src/main/java/com/aventum/scanner/MainActivity.kt` | Écran principal : scan, enregistrement, partage |
| `app/src/main/java/com/aventum/scanner/MediaStorage.kt` | Écriture dans la galerie et les Téléchargements |
| `app/src/main/java/com/aventum/scanner/PdfMaker.kt` | Création des PDF page par page |
| `app/src/main/java/com/aventum/scanner/TextExtractor.kt` | Reconnaissance de texte (OCR) |
| `app/src/main/java/com/aventum/scanner/DocumentWriters.kt` | Écriture des fichiers TXT et DOCX |
| `app/src/main/java/com/aventum/scanner/PageAdapter.kt` | Miniatures des pages scannées |
