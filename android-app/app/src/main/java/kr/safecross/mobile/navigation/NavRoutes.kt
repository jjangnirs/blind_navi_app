package kr.safecross.mobile.navigation

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Destination : Screen("destination")
    data object RouteSummary : Screen("route_summary")
    data object Navigation : Screen("navigation")
    data object CrossingAssist : Screen("crossing_assist")
    data object Settings : Screen("settings")
}
