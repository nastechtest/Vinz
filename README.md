🎧 Vinz

<p align="center">
  <strong>A community-driven Android client for YouTube Music.</strong>
  <br />
  An independent project built on open-source work, with its own identity under the Vinz name.
</p><p align="center">
  <a href="https://github.com/nastechtest/Vinz">
    <img src="https://img.shields.io/badge/Project-Vinz-8B5CF6?style=for-the-badge&logo=android&logoColor=white" alt="Vinz Android project" />
  </a>
  <a href="https://www.gnu.org/licenses/gpl-3.0.html">
    <img src="https://img.shields.io/badge/License-GPL--3.0--or--later-22C55E?style=for-the-badge" alt="GPL-3.0-or-later license" />
  </a>
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin namespace" />
</p><p align="center">
  <a href="#-overview">Overview</a> •
  <a href="#-features-and-scope">Features</a> •
  <a href="#-getting-started">Build</a> •
  <a href="#-testing">Testing</a> •
  <a href="#-ci-and-releases">Releases</a> •
  <a href="#-license-and-attribution">License</a>
</p>---

📖 Overview

Vinz is an unofficial Android client for YouTube Music, based on open-source work by Yigder.

The project aims to maintain an independently developed Android application while preserving the original authors' copyright notices, third-party attributions, and applicable open-source licensing requirements.

Vinz is a community project. It is not affiliated with, endorsed by, or sponsored by Google or YouTube.

✨ Project at a glance

Property| Details
Project name| Vinz
Platform| Android
Application ID| "com.vinz.music"
Kotlin namespace| "com.vinz.music"
Language| Kotlin
Build system| Gradle
Android build plugin| Android Gradle Plugin, as configured in the repository
License| GNU GPL-3.0-or-later
Upstream attribution| Yigder and original component authors
Repository| "nastechtest/Vinz" (https://github.com/nastechtest/Vinz)
Distribution| Build locally or use a project-published release when available

«[!IMPORTANT]
Vinz is an unofficial third-party client. YouTube, YouTube Music, and Google are trademarks of their respective owners. This project does not claim ownership of their services, branding, or content.»

🎯 Features and scope

Vinz is an Android application project focused on accessing YouTube Music through an independently developed client.

Area| Project scope
Android application| Native Android project
Music service| YouTube Music
Application identity| "com.vinz.music"
Development| Local Gradle builds
Quality assurance| Unit tests and automated CI checks
Integration testing| Some tests communicate with live music or lyrics endpoints
Licensing| GPL-3.0-or-later
Source attribution| Retains applicable upstream copyright and component notices

This table describes the project's established scope and development setup; it is not a claim that every possible YouTube Music feature is implemented.

🚀 Getting started

There are two main ways to work with Vinz:

- Build from source: compile the application yourself.
- Run the tests: check the local unit-test suite before making or contributing changes.

Prerequisites

Install the required development tools before building.

Requirement| Version or configuration
Java Development Kit| JDK 17 or newer
Android SDK platform| API 37
Android SDK command-line tools| Required
Gradle| Use the repository's Gradle Wrapper
Network connection| Recommended for dependency downloads; required for live endpoint integration tests

The project's Gradle configuration remains the source of truth for the exact Android Gradle Plugin, Gradle, Kotlin, and other dependency versions.

1. Clone the repository

git clone https://github.com/nastechtest/Vinz.git
cd Vinz

2. Build the debug APK

Run the Android application build:

./gradlew :app:assembleDebug

On a successful build, Gradle normally writes the debug APK to:

app/build/outputs/apk/debug/app-debug.apk

The exact output depends on the repository's Gradle configuration.

3. Install on a connected Android device

Enable USB debugging on your Android device, connect it to your computer, and verify that ADB can see it:

adb devices

Install the debug APK:

adb install -r app/build/outputs/apk/debug/app-debug.apk

Alternatively, open the project in Android Studio, allow Gradle synchronization to complete, select the "app" configuration, and run the application on an available device or emulator.

«[!NOTE]
Debug builds are intended for development and testing. They are not substitutes for properly signed production releases.»

🧪 Testing

Vinz provides a local unit-test task through Gradle.

Run the unit-test suite:

./gradlew :app:testDebugUnitTest

Testing reference

Task| Command| Purpose
Build debug APK| "./gradlew :app:assembleDebug"| Compile the debug application
Run unit tests| "./gradlew :app:testDebugUnitTest"| Execute the configured debug unit tests
Run build and tests| Run both commands above| Check compilation and unit-test results

Network-dependent tests

Some test classes communicate with live YouTube Music or lyrics endpoints. These tests may require an active internet connection and can be affected by remote service availability or changes.

The required CI test set excludes the network-dependent integration tests from its offline test requirements.

A passing offline CI run does not necessarily mean every live integration test has passed. When investigating service integration issues, distinguish local unit-test failures from failures caused by remote endpoints.

⚙️ Continuous integration

Pull requests and pushes to the "main" branch run the project's Android build and unit-test workflow.

The purpose of these checks is to catch compilation and test failures before changes are merged.

Event| Expected automation
Pull request| Run the configured Android build and unit-test workflow
Push to "main"| Run the configured Android build and unit-test workflow
Push of a "v*" tag| Trigger the configured signed APK release workflow

The repository's actual GitHub Actions workflow files determine the precise jobs, permissions, triggers, and required checks.

📦 Releases and APK signing

Vinz uses a release workflow triggered by version tags matching "v*".

For example, a future release tag could follow this format:

v1.0.0

The example is a versioning illustration, not a claim that this release exists.

Release signing requirements

Before the first signed APK release, configure the following GitHub Actions repository secrets with the persistent release signing key:

Secret| Purpose
"ANDROID_KEYSTORE_BASE64"| Base64-encoded Android release keystore
"ANDROID_KEYSTORE_PASSWORD"| Password protecting the keystore
"ANDROID_KEY_ALIAS"| Alias identifying the signing key
"ANDROID_KEY_PASSWORD"| Password for the signing key

Security requirements

- Keep keystore files and passwords out of Git.
- Never place signing credentials in source code, logs, or public workflow output.
- Use the same protected release key for subsequent updates to preserve Android signing continuity.
- Restrict access to repository secrets and release permissions.
- Never distribute a debug-signed APK as a production release.
- Verify the release artifact and its signature before publishing it.

The release workflow intentionally fails if its required signing secrets are absent. This prevents an incomplete signing configuration from silently producing an incorrectly signed production artifact.

Obtaining a release

Visit the repository's "Releases page" (https://github.com/nastechtest/Vinz/releases) to check whether a published APK is available.

If no release is listed, build the application locally using the instructions above.

🏗️ Development and contribution

Contributions that improve correctness, reliability, maintainability, testing, documentation, and compatibility are welcome, subject to the project's contribution policies.

Recommended development workflow

1. Fork the repository and create a dedicated branch.
2. Review the existing implementation before changing behavior.
3. Make focused changes that preserve upstream attribution and license obligations.
4. Run the relevant unit tests.
5. Build the Android application.
6. Review the final diff for unintended changes, exposed credentials, and generated files.
7. Submit a pull request describing the changes and verification performed.

Before submitting a change, run:

./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug

Include any known limitations, network dependencies, and steps needed to reproduce a failure.

🛡️ Privacy, security, and responsible use

Vinz is an unofficial third-party client. Users should understand that interactions with YouTube Music and other remote services remain subject to the behavior, availability, and applicable terms of those services.

- Do not commit authentication tokens, passwords, or private signing keys.
- Report security vulnerabilities privately to the repository maintainers rather than publishing exploitable details in an issue.
- Review upstream and third-party dependency licenses when making changes.
- Do not assume that using an unofficial client bypasses the policies or restrictions of a remote service.
- Do not use the project's name or branding to imply official Google or YouTube endorsement.

❓ Troubleshooting

Problem| What to check
Gradle build fails| Verify JDK compatibility, Android SDK installation, and dependency downloads
SDK platform is missing| Install Android SDK platform 37 using the SDK Manager
Unit tests fail| Inspect the failing test output and determine whether the test requires a network connection
A live endpoint test fails| Check connectivity, remote service availability, and changes to the endpoint
ADB cannot find the device| Check USB debugging, device authorization, cable connection, and "adb devices"
Release workflow fails| Verify that all four signing secrets exist and match the persistent release keystore
APK installation reports a signature conflict| Check whether the installed application was signed with a different key; back up relevant data before considering an uninstall

📄 License and attribution

Vinz is distributed under the GNU General Public License v3.0 or later (GPL-3.0-or-later).

The project is based on open-source work by Yigder. Original copyright notices, applicable attribution, and third-party component notices must remain intact.

- License: ""LICENSE"" (LICENSE)
- Fork information: ""FORK_NOTICE.md"" (FORK_NOTICE.md)
- Source repository: "nastechtest/Vinz" (https://github.com/nastechtest/Vinz)

If you redistribute modified or unmodified versions, comply with the applicable GPL requirements, including the relevant source-code, license, copyright-notice, and modification-notice obligations.

For the full terms, consult the license included with this repository.

🙏 Acknowledgements

Thank you to Yigder, the original contributors, and the maintainers of the open-source components on which this project depends.

Vinz builds on existing open-source work and aims to maintain that attribution as the project evolves.

---

<p align="center">
  <strong>Vinz</strong>
  <br />
  An independent Android music client.
  <br /><br />
  <a href="https://github.com/nastechtest/Vinz">Source code</a> ·
  <a href="https://github.com/nastechtest/Vinz/issues">Issues</a> ·
  <a href="https://github.com/nastechtest/Vinz/releases">Releases</a>
</p><p align="center">
  <sub>Not affiliated with Google or YouTube. All third-party trademarks belong to their respective owners.</sub>
</p>