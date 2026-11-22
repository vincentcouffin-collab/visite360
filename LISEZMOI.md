# Visite 360 betCVC – application Android

Application pour téléphone Android (Android 10 ou plus récent) : visite virtuelle au joystick
à partir de photos 360, connexion directe à la caméra Insta360 (X5) en Wi-Fi, relevé de pièces
et export vers Revit (DXF + JSON + CSV).

## Obtenir le fichier APK

### Option A – GitHub (sans PC)
1. Créer un dépôt privé sur github.com et y déposer tout le contenu de ce dossier (branche `main`).
2. Onglet **Actions** : le travail « APK Visite 360 » se lance tout seul (3 à 5 minutes).
3. Onglet **Releases** : télécharger `Visite360.apk` depuis le téléphone et l'ouvrir pour l'installer.

### Option B – PC avec Android Studio
1. Installer Android Studio (developer.android.com/studio), ouvrir ce dossier.
2. Menu **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
3. Copier `app/build/outputs/apk/debug/app-debug.apk` sur le téléphone et l'ouvrir.

À l'installation, Android demande d'autoriser l'installation depuis cette source : accepter.
Le projet est signé avec la clé `app/visite360.jks` : les mises à jour s'installent par-dessus
l'ancienne version sans perdre les visites. Ne pas perdre ce fichier.

## Utilisation avec la caméra
1. Sur la X5 : activer « Assemblage dans la caméra » dans les réglages photo 360 (fichiers JPG).
2. Allumer la caméra ; dans l'application, **Caméra > Réglages Wi-Fi** et choisir le réseau de la caméra.
3. **Connecter / actualiser** : état de la caméra et liste des photos de la carte.
4. **Prendre une photo 360** (retardateur réglable) ou **Importer toutes les nouvelles photos**.

Chaque photo importée devient un point de vue de la visite en cours. Les originaux sont rangés
dans la galerie : `Pictures/Visite360/<nom de la visite>`. Les exports Revit vont dans
`Téléchargements/Visite360`.

## Structure
- `app/src/main/assets/www/index.html` : toute l'interface (visite, relevé, export, panneau caméra).
- `app/src/main/java/fr/betcvc/visite360/MainActivity.java` : coque Android (page embarquée,
  dialogue OSC avec la caméra sur `http://192.168.42.1`, stockage des visites, exports).

## État des vérifications
- Interface testée dans un navigateur avec une caméra simulée (connexion, liste, import,
  prise de vue, visites, export).
- Code Java vérifié par compilation de contrôle ; l'APK n'a pas encore été compilé ni essayé
  sur un téléphone, et le dialogue n'a pas été essayé avec une vraie X5.
