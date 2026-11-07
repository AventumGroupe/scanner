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
3. Le document rejoint la liste **Mes scans** de l'écran d'accueil (du plus récent au plus ancien).
   Juste après le scan, puis en touchant un scan de la liste, on choisit :
   - **Ouvrir** ;
   - **Enregistrer** : les JPG vont dans la galerie, album **Scans** (`Images/Scans`) ;
     les PDF, TXT et DOCX dans **Téléchargements/Scans** ;
   - **Envoyer par e-mail** : uniquement les applications de messagerie (Gmail, Outlook…), fichiers en pièce jointe ;
   - **Partager** : toutes les applications installées (Messages, WhatsApp, Drive, Teams…) ;
   - **Supprimer** : retire le scan de la liste (les copies enregistrées sont conservées).

La reconnaissance de texte (OCR) se fait sur le téléphone : les documents ne sont pas envoyés sur internet.
Le scan s'appuie sur le scanner de documents Google (ML Kit), fourni par les services Google Play
— aucune autorisation « appareil photo » n'est donc nécessaire.

## Installer / mettre à jour l'application

Sur le téléphone, ouvrir ce lien (toujours la dernière version) :

**https://github.com/AventumGroupe/scanner/releases/latest/download/AventumScan.apk**

puis ouvrir le fichier téléchargé et toucher **Installer** (la première fois, autoriser
« l'installation d'applications inconnues »). Une mise à jour s'installe directement
par-dessus la version précédente.

### Publier une nouvelle version

1. Augmenter `versionCode` et `versionName` dans `app/build.gradle.kts`.
2. Pousser : GitHub Actions compile, signe l'APK et crée la Release `v<versionName>`.

La signature utilise deux secrets du dépôt (*Settings › Secrets and variables › Actions*) :
`SIGNING_KEYSTORE` (fichier `.jks` encodé en base64) et `SIGNING_PASSWORD`.
Sans eux, l'APK est signé avec une clé temporaire (artefact uniquement, pas de Release).

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
| `app/src/main/java/com/aventum/scanner/MainActivity.kt` | Écran d'accueil : format, scan, actions |
| `app/src/main/java/com/aventum/scanner/MediaStorage.kt` | Écriture dans la galerie et les Téléchargements |
| `app/src/main/java/com/aventum/scanner/PdfMaker.kt` | Création des PDF page par page |
| `app/src/main/java/com/aventum/scanner/TextExtractor.kt` | Reconnaissance de texte (OCR) |
| `app/src/main/java/com/aventum/scanner/DocumentWriters.kt` | Écriture des fichiers TXT et DOCX |
| `app/src/main/java/com/aventum/scanner/ScanStore.kt` | Historique « Mes scans » (stockage privé) |
| `app/src/main/java/com/aventum/scanner/ScanAdapter.kt` | Affichage de la liste « Mes scans » |
