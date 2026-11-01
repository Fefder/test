package com.edgeai.agent.security

/** Risk classification for a foreground or target Android package. */
enum class PackageClassification {
    UNKNOWN,
    BLOCKED,
    TRUSTED,
    UNCLASSIFIED,
}

data class PackageAssessment(
    val packageName: String?,
    val classification: PackageClassification,
) {
    val blocked: Boolean
        get() =
            classification == PackageClassification.UNKNOWN ||
                classification == PackageClassification.BLOCKED
}

/**
 * Pure package policy shared by Accessibility, Shizuku and future MCP handlers.
 * Unknown foreground packages deliberately fail closed.
 */
object PackageShieldFilter {
    const val ERR_FINANCIAL_SHIELD_BLOCKED = "ERR_FINANCIAL_SHIELD_BLOCKED"
    const val ERR_FOREGROUND_UNKNOWN = "ERR_FOREGROUND_UNKNOWN"

    private val blockedPackages =
        setOf(
            // Ukrainian banking, identity and government-document applications.
            "com.ftband.mono", // monobank
            "ua.privatbank.ap24", // Privat24
            "ua.privatbank.cb", // Privat24 for business
            "ua.gov.diia.app", // Diia
            "ua.pumb.mobile",
            "com.ukrsibbank.ukrsibonline",
            "ua.raiffeisen.avalpay",
            "com.sensebank.sense",
            "ua.alfabank.mobile.android",
            "ua.oschadbank.oschad24",
            // Payments and crypto wallets.
            "com.google.android.apps.walletnfcrel",
            "com.paypal.android.p2pmobile",
            "com.binance.dev",
            "com.revolut.revolut",
            "com.wallet.crypto.trustapp",
            "io.metamask",
            // Credentials, passwords and 2FA.
            "com.google.android.apps.authenticator2",
            "com.authy.authy",
            "com.agilebits.onepassword",
            "com.bitwarden.android",
            "com.lastpass.lpandroid",
            "com.dashlane",
            // Never automate credential or permission changes in system settings.
            "com.android.settings",
        )

    private val trustedAutomationPackages =
        setOf(
            "com.edgeai.agent",
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",
            "com.whatsapp",
            "com.instagram.android",
        )

    private val blockedSegmentPattern =
        Regex(
            pattern = "(^|\\.)(bank|banking|wallet|crypto|finance|fintech|authenticator|password|passcode|payment|payments)(\\.|$)",
            option = RegexOption.IGNORE_CASE,
        )

    fun classify(packageName: String?): PackageAssessment {
        val normalized =
            packageName?.trim()?.takeIf { it.isNotEmpty() }
                ?: return PackageAssessment(null, PackageClassification.UNKNOWN)

        val classification =
            when {
                normalized in blockedPackages -> PackageClassification.BLOCKED
                blockedSegmentPattern.containsMatchIn(normalized) -> PackageClassification.BLOCKED
                normalized in trustedAutomationPackages -> PackageClassification.TRUSTED
                else -> PackageClassification.UNCLASSIFIED
            }
        return PackageAssessment(normalized, classification)
    }

    fun isPackageBlocked(packageName: String?): Boolean = classify(packageName).blocked

    fun isPackageTrusted(packageName: String?): Boolean = classify(packageName).classification == PackageClassification.TRUSTED

    fun validateAction(packageName: String?): Result<Unit> {
        val assessment = classify(packageName)
        return when (assessment.classification) {
            PackageClassification.UNKNOWN ->
                Result.failure(SecurityException(ERR_FOREGROUND_UNKNOWN))
            PackageClassification.BLOCKED ->
                Result.failure(SecurityException(ERR_FINANCIAL_SHIELD_BLOCKED))
            PackageClassification.TRUSTED,
            PackageClassification.UNCLASSIFIED,
            -> Result.success(Unit)
        }
    }
}
