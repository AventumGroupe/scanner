# Aventum Scan — scanner de documents Android

Application Android très simple :

1. **Choisir le format** des fichiers (le choix est mémorisé) :
   - **JPG** – un fichier par page ;
   - **PDF** – un fichier par page ;
   - **PDF** – un seul fichier pour toutes les pages (scan en lot) ;
   - **OCR texte imprimé – Word** : le texte de toutes les pages, reconnu sur le téléphone
     (gratuit, rapide, hors ligne), dans un seul document Word ;
   - **OCR texte manuscrit – Word** : le texte de toutes les pages, y compris écrit à la main,
     lu par l'IA Claude et réuni dans un seul document Word (un saut de page entre chaque page).
2. **Scanner** un document avec l'appareil photo (détection automatique des bords,
   recadrage, filtres noir & blanc, plusieurs pages, import depuis la galerie possible).
3. Le document rejoint la liste **Mes scans** de l'écran d'accueil (du plus récent au plus ancien).
   Juste après le scan, puis en touchant un scan de la liste, des icônes permettent de :
   - **Ouvrir** ;
   - **Enregistrer** : les JPG vont dans la galerie, album **Scans** (`Images/Scans`) ;
     les PDF et documents Word dans **Téléchargements/Scans** ;
   - **Envoyer par e-mail** : uniquement les applications de messagerie (Gmail, Outlook…), fichiers en pièce jointe ;
   - **Partager** : toutes les applications installées (Messages, WhatsApp, Drive, Teams…) ;
   - **Supprimer** : retire le scan de la liste (les copies enregistrées sont conservées).

### OCR texte manuscrit (IA)

Le mode « OCR texte manuscrit » envoie chaque page à l'API Claude d'Anthropic (modèle `claude-opus-5-5`),
qui lit aussi bien l'écriture manuscrite que le texte imprimé. On scanne toutes les pages à la suite
(jusqu'à 30) ; elles sont lues l'une après l'autre, avec une barre de progression et un bouton
**Annuler**, puis réunies dans un seul document Word.
Ce mode nécessite :

- une **clé d'API Anthropic** (console.anthropic.com), saisie une fois dans l'application
  (icône **clé** en haut de l'écran), chiffrée et conservée uniquement sur le téléphone ;
- une **connexion internet** ; l'usage est facturé par Anthropic sur le compte de la clé.

Les pages sont alors **envoyées à Anthropic** : à éviter pour les documents confidentiels.

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

- Android 8.0 (API 26) ou plus récent, téléphone 64 bits (quasiment tous depuis 2017-2018)
- Services Google Play (présents sur la quasi-totalité des téléphones Android)

## Structure

| Fichier | Rôle |
|---|---|
| `app/src/main/java/com/aventum/scanner/MainActivity.kt` | Écran d'accueil : format, scan, actions |
| `app/src/main/java/com/aventum/scanner/ScanViewModel.kt` | Traitements longs (OCR, enregistrement), progression |
| `app/src/main/java/com/aventum/scanner/SecretStore.kt` | Clé API chiffrée (Keystore Android) |
| `app/src/main/java/com/aventum/scanner/MediaStorage.kt` | Écriture dans la galerie et les Téléchargements |
| `app/src/main/java/com/aventum/scanner/PdfMaker.kt` | Création des PDF page par page |
| `app/src/main/java/com/aventum/scanner/TextExtractor.kt` | OCR texte imprimé, sur le téléphone |
| `app/src/main/java/com/aventum/scanner/ClaudeTranscriber.kt` | OCR texte manuscrit, par l'API Claude |
| `app/src/main/java/com/aventum/scanner/DocumentWriters.kt` | Écriture du document Word (DOCX) |
| `app/src/main/java/com/aventum/scanner/ScanStore.kt` | Historique « Mes scans » (stockage privé) |
| `app/src/main/java/com/aventum/scanner/ScanAdapter.kt` | Affichage de la liste « Mes scans » |
