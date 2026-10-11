package com.wax.module.modern

/**
 * Localization for the embedded Control Center (#433).
 *
 * The shell is built programmatically inside WhatsApp, and the modern
 * runtime module carries no AndroidX or bundled resource table, so it cannot
 * resolve strings from a layout XML or from the module's own resources. It
 * therefore owns a small, explicit string table instead of hardcoding English
 * text into the views.
 *
 * Selection is by the target's own current locale language, with English as
 * the fallback, so the surface follows the device language the user already
 * chose for WhatsApp. Missing translations degrade to English rather than
 * showing an empty label.
 */
object ControlCenterStrings {
    const val LANGUAGE_ARABIC = "ar"

    data class Table(
        val title: String,
        val searchHint: String,
        val noResults: String,
        val restart: String,
        val openManager: String,
        val favoritesOnly: String,
        val favorites: String,
        val favoriteToggleOff: String,
        val favoriteToggleOn: String,
        val markFavorite: String,
        val disabled: String,
        val hideAfterClicks: String,
        val hideWhileHolding: String,
        val runDiagnostics: String,
        val allFeatures: String,
        val restartConfirmation: String,
        val loading: String,
        val stateUnavailable: String,
        val profiles: String,
        val manageProfiles: String,
        val profilesGlobalScope: String,
        val profileSaveFailed: String,
        val defaultProfile: String,
    )

    private val english = Table(
        title = "WA X",
        searchHint = "Search WA X features",
        noResults = "No WA X feature matches your search",
        restart = "Restart WhatsApp",
        openManager = "WA X Manager",
        favoritesOnly = "Favourites only",
        favorites = "Favourites",
        favoriteToggleOff = "Add to favourites",
        favoriteToggleOn = "Remove from favourites",
        markFavorite = "Mark as favourite",
        disabled = "Disabled",
        hideAfterClicks = "Hide after click count",
        hideWhileHolding = "Hide while holding the title",
        runDiagnostics = "Run diagnostics",
        allFeatures = "All",
        restartConfirmation = "Restart WhatsApp to apply pending changes?",
        loading = "Loading WA X settings…",
        stateUnavailable = "Could not read WA X settings. Open Manager or reopen this panel.",
        profiles = "Profiles",
        manageProfiles = "Manage profiles",
        profilesGlobalScope = "These profiles currently share global settings for WhatsApp and Business.",
        profileSaveFailed = "Could not switch profile. Settings were not confirmed.",
        defaultProfile = "Default",
    )

    private val arabic = Table(
        title = "WA X",
        searchHint = "ابحث في مزايا WA X",
        noResults = "لا توجد ميزة WA X مطابقة للبحث",
        restart = "إعادة تشغيل واتساب",
        openManager = "مدير WA X",
        favoritesOnly = "المفضلة فقط",
        favorites = "المفضلة",
        favoriteToggleOff = "إضافة إلى المفضلة",
        favoriteToggleOn = "إزالة من المفضلة",
        markFavorite = "تعيين كمفضلة",
        disabled = "معطّل",
        hideAfterClicks = "إخفاء بعد عدد الضغطات",
        hideWhileHolding = "إخفاء أثناء الضغط على العنوان",
        runDiagnostics = "تشخيص ذاتي",
        allFeatures = "الكل",
        restartConfirmation = "هل تريد إعادة تشغيل واتساب لتطبيق التغييرات؟",
        loading = "جارٍ تحميل إعدادات WA X…",
        stateUnavailable = "تعذر قراءة الإعدادات. افتح مدير WA X أو أعد فتح اللوحة.",
        profiles = "البروفايلات",
        manageProfiles = "إدارة البروفايلات",
        profilesGlobalScope = "هذه البروفايلات تستخدم حاليًا الإعدادات العامة المشتركة بين واتساب وواتساب للأعمال.",
        profileSaveFailed = "تعذر التبديل إلى البروفايل المطلوب.",
        defaultProfile = "الافتراضي",
    )

    /** Unknown languages fall back to English instead of rendering blanks. */
    fun forLanguage(language: String?): Table {
        if (language?.lowercase()?.startsWith(LANGUAGE_ARABIC) == true) return arabic
        val code = when (val tag = language?.lowercase()?.substringBefore('-')?.substringBefore('_')) {
            "iw", "he" -> "he"
            "in", "id" -> "id"
            null -> "en"
            else -> tag
        }
        val translated = ControlCenterLocaleCatalog.values(code)
        return english.copy(
            searchHint = translated["ui.search"] ?: english.searchHint,
            noResults = translated["ui.empty"] ?: english.noResults,
            restart = translated["ui.restart"] ?: english.restart,
            allFeatures = translated["ui.all"] ?: english.allFeatures,
            profiles = translated["ui.profiles"] ?: english.profiles,
            manageProfiles = translated["ui.profileManage"] ?: english.manageProfiles,
            profilesGlobalScope = translated["ui.profileScope"] ?: english.profilesGlobalScope,
            defaultProfile = translated["ui.profileDefault"] ?: english.defaultProfile,
        )
    }
}