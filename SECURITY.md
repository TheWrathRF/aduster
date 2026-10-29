# Security

Aduster decrypts browser traffic on the phone it runs on, so a flaw in it can matter more than a
flaw in an ordinary app. Reports are welcome.

## Reporting a problem

Report it privately, not as a public issue:

- through GitHub's private vulnerability reporting (Security › Report a vulnerability on this
  repository), or
- by email to arda@aduster.site.

Please include the Aduster version (Settings › About), the Android version, and the steps that
show the problem. The aim is to answer within a week. Once a fix is released, the reporter is
credited in the release notes unless they would rather not be named.

## What counts

Anything in the latest release, including the parts of the APK whose source is not published
(the page engine). For example:

- another app on the phone reaching the local proxy or its page endpoint in a way it should not;
- a page decrypted that should never be (banks, payments, sign-in, government sites), or a
  certificate error hidden from the browser;
- the filtering certificate's private key leaving the app, or being usable by another app;
- a crafted DNS answer, filter list or web page that crashes the engine or runs code.

Ads or trackers that get through, and sites that break, are ordinary bugs: open an issue for
those.

## Supported versions

Only the latest release gets fixes. There is no update channel inside the app: new versions are
published in this repository's Releases.
