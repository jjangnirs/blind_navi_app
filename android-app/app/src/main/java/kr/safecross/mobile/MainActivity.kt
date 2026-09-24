package kr.safecross.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kr.safecross.mobile.accessibility.TtsAnnouncementHelper
import kr.safecross.mobile.data.repository.SharedPrefsRecentDestinationRepository
import kr.safecross.mobile.data.repository.TmapRouteRepository
import kr.safecross.mobile.domain.model.LocationPoint
import kr.safecross.mobile.location.ProductionLocationSource
import kr.safecross.mobile.navigation.Screen
import kr.safecross.mobile.service.NavigationForegroundService
import kr.safecross.mobile.ui.screens.destination.DestinationScreen
import kr.safecross.mobile.ui.screens.destination.DestinationViewModel
import kr.safecross.mobile.ui.screens.navigation.NavigationScreen
import kr.safecross.mobile.ui.screens.navigation.NavigationViewModel
import kr.safecross.mobile.ui.screens.onboarding.OnboardingScreen
import kr.safecross.mobile.ui.screens.onboarding.OnboardingViewModel
import kr.safecross.mobile.ui.screens.route.RouteSummaryScreen
import kr.safecross.mobile.ui.screens.route.RouteSummaryViewModel
import kr.safecross.mobile.ui.screens.settings.SettingsScreen
import kr.safecross.mobile.ui.screens.settings.SettingsViewModel
import kr.safecross.mobile.ui.theme.HighContrastBlack
import kr.safecross.mobile.ui.theme.SafeCrossTheme

class MainActivity : ComponentActivity() {

    private var ttsHelper: TtsAnnouncementHelper? = null
    private lateinit var locationSource: ProductionLocationSource
    private val routeRepository = TmapRouteRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kr.safecross.mobile.perception.PerceptionFlightRecorder.init(applicationContext)
        kr.safecross.mobile.navigation.NavigationFlightRecorder.init(applicationContext)
        ttsHelper = TtsAnnouncementHelper(this)
        locationSource = ProductionLocationSource(this)

        val onboardingViewModel = OnboardingViewModel()
        val destinationViewModel = DestinationViewModel(
            recentRepository = SharedPrefsRecentDestinationRepository(this)
        )
        val routeSummaryViewModel = RouteSummaryViewModel(routeRepository)
        val navigationViewModel = NavigationViewModel(locationSource = locationSource, routeRepository = routeRepository)
        val settingsViewModel = SettingsViewModel()

        setContent {
            SafeCrossTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = HighContrastBlack
                ) {
                    val navController = rememberNavController()

                    // Android 14/15 런타임 위치, 마이크 및 알림 권한 런처 (Galaxy S25 Ultra 필수 대응)
                    val permissionsToRequest = remember {
                        val list = mutableListOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.RECORD_AUDIO
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            list.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        list.toTypedArray()
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                        if (fineGranted || coarseGranted) {
                            locationSource.startTracking()
                        }
                    }

                    // 앱 시작 시 권한 상태 확인 및 미보유 시 요청
                    LaunchedEffect(Unit) {
                        if (locationSource.hasFineLocationPermission()) {
                            locationSource.startTracking()
                        } else {
                            permissionLauncher.launch(permissionsToRequest)
                        }
                    }

                    // 스마트폰 실시간 GPS 위치 수신 및 Geocoder 주소 역지오코딩
                    val currentGps = remember { mutableStateOf<LocationPoint?>(null) }
                    val currentAddress = remember { mutableStateOf<String?>(null) }
                    val context = androidx.compose.ui.platform.LocalContext.current

                    LaunchedEffect(locationSource) {
                        locationSource.locationUpdates.collect { sample ->
                            val point = LocationPoint(lat = sample.lat, lon = sample.lon)
                            currentGps.value = point

                            // 비동기 역지오코딩을 통해 한글 도로명/지번 주소 추출
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val resolvedAddr = try {
                                    val geocoder = android.location.Geocoder(context, java.util.Locale.KOREA)
                                    val addresses = geocoder.getFromLocation(sample.lat, sample.lon, 1)
                                    val first = addresses?.firstOrNull()
                                    val line = first?.getAddressLine(0)?.replace("대한민국 ", "")
                                    if (!line.isNullOrBlank()) {
                                        line
                                    } else {
                                        "현재 위치 (위도 ${String.format("%.4f", sample.lat)}, 경도 ${String.format("%.4f", sample.lon)})"
                                    }
                                } catch (_: Exception) {
                                    "현재 GPS 위치 (${String.format("%.4f", sample.lat)}, 경도 ${String.format("%.4f", sample.lon)})"
                                }

                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    currentAddress.value = resolvedAddr
                                    destinationViewModel.updateCurrentLocation(
                                        lat = sample.lat,
                                        lon = sample.lon,
                                        address = resolvedAddr,
                                        signalPercent = sample.signalStrengthPercent,
                                        accuracyMeters = sample.accuracyMeters
                                    )
                                    // 경로 요약 화면에 진입한 상태에서 GPS가 갱신되어 초기 위치와 다를 경우 경로 즉시 자동 재탐색
                                    routeSummaryViewModel.updateOriginIfGpsMoved(
                                        newGps = LocationPoint(sample.lat, sample.lon),
                                        newAddress = resolvedAddr
                                    )
                                }
                            }
                        }
                    }

                    SafeCrossNavHost(
                        navController = navController,
                        ttsHelper = ttsHelper,
                        locationSource = locationSource,
                        currentGps = currentGps.value,
                        currentAddress = currentAddress.value,
                        onboardingViewModel = onboardingViewModel,
                        destinationViewModel = destinationViewModel,
                        routeSummaryViewModel = routeSummaryViewModel,
                        navigationViewModel = navigationViewModel,
                        settingsViewModel = settingsViewModel
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationSource.stopTracking()
        ttsHelper?.shutdown()
        ttsHelper = null
    }
}

@Composable
fun SafeCrossNavHost(
    navController: NavHostController,
    ttsHelper: TtsAnnouncementHelper?,
    locationSource: ProductionLocationSource,
    currentGps: LocationPoint?,
    currentAddress: String? = null,
    onboardingViewModel: OnboardingViewModel,
    destinationViewModel: DestinationViewModel,
    routeSummaryViewModel: RouteSummaryViewModel,
    navigationViewModel: NavigationViewModel,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Onboarding.route,
        modifier = modifier
    ) {
        // 1. Onboarding Screen
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                viewModel = onboardingViewModel,
                voiceAnnouncer = ttsHelper,
                onNavigateToDestination = {
                    navController.navigate(Screen.Destination.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        // 2. Destination Screen
        composable(Screen.Destination.route) {
            DestinationScreen(
                viewModel = destinationViewModel,
                voiceAnnouncer = ttsHelper,
                currentGps = currentGps,
                onNavigateToRouteSummary = { selectedDest ->
                    // 스마트폰의 실제 GPS 좌표 및 Geocoder 도로명 주소를 출발지로 자동 설정
                    val originPoint = currentGps ?: LocationPoint(35.1595, 126.8526)
                    val originName = currentAddress ?: if (currentGps != null) "현재 GPS 위치" else "출발지"

                    routeSummaryViewModel.loadRoute(
                        origin = originPoint,
                        destination = selectedDest.location,
                        originName = originName,
                        destinationName = selectedDest.name,
                        excludeStairs = true
                    )
                    navController.navigate(Screen.RouteSummary.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        // 3. Route Summary Screen
        composable(Screen.RouteSummary.route) {
            val context = androidx.compose.ui.platform.LocalContext.current
            RouteSummaryScreen(
                viewModel = routeSummaryViewModel,
                voiceAnnouncer = ttsHelper,
                onNavigateToNavigation = { route ->
                    // FGS 시작 및 엔진 가동
                    NavigationForegroundService.startService(
                        context = context,
                        statusMessage = "보행 안내 중",
                        maneuverText = route.maneuvers.firstOrNull()?.instruction ?: ""
                    )
                    // 실제 스마트폰 하드웨어 GPS 소스를 네비게이션 엔진에 주입
                    navigationViewModel.setRoute(route, source = locationSource)
                    navController.navigate(Screen.Navigation.route)
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // 4. Navigation Screen (4-stage walking mode)
        composable(Screen.Navigation.route) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val routeState by routeSummaryViewModel.uiState.collectAsState()
            val settingsState by settingsViewModel.uiState.collectAsState()
            val hapticHelper = androidx.compose.runtime.remember {
                kr.safecross.mobile.accessibility.HapticFeedbackHelper(context)
            }.apply {
                isEnabled = settingsState.isVibrationEnabled
                intensity = settingsState.vibrationIntensity
            }
            val poseTracker = androidx.compose.runtime.remember {
                kr.safecross.mobile.sensor.ProductionDevicePoseTracker(context)
            }
            androidx.compose.runtime.LaunchedEffect(poseTracker) {
                navigationViewModel.setDevicePoseTracker(poseTracker)
            }
            val route = routeState.route
            if (route != null) {
                NavigationScreen(
                    route = route,
                    viewModel = navigationViewModel,
                    voiceAnnouncer = ttsHelper,
                    hapticFeedbackHelper = hapticHelper,
                    onOpenCrossingAssist = {
                        navController.navigate(Screen.CrossingAssist.route)
                    },
                    onStopNavigation = {
                        // FGS 중지
                        NavigationForegroundService.stopService(context)
                        navigationViewModel.stopNavigation()
                        navController.navigate(Screen.Destination.route) {
                            popUpTo(Screen.Destination.route) { inclusive = false }
                        }
                    }
                )
            }
        }

        // 5. Crossing Assist Screen (카메라 횡단 보조, SR-F-040)
        composable(Screen.CrossingAssist.route) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val settingsState by settingsViewModel.uiState.collectAsState()
            val hapticHelper = androidx.compose.runtime.remember {
                kr.safecross.mobile.accessibility.HapticFeedbackHelper(context)
            }.apply {
                isEnabled = settingsState.isVibrationEnabled
                intensity = settingsState.vibrationIntensity
            }
            val crossingAssistViewModel: kr.safecross.mobile.ui.screens.crossingassist.CrossingAssistViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel {
                    kr.safecross.mobile.ui.screens.crossingassist.CrossingAssistViewModel(
                        cameraPipeManager = kr.safecross.mobile.camera.ProductionCameraPipeManager(context),
                        crosswalkEstimator = kr.safecross.mobile.perception.FakeCrosswalkEstimator(),
                        signalEstimator = kr.safecross.mobile.perception.TwoTierHybridSignalEstimator.createDefault(context),
                        signalAssociator = kr.safecross.mobile.perception.FakeSignalAssociator(),
                        decisionEngine = kr.safecross.mobile.decision.CrossingDecisionEngine(),
                        poseTracker = kr.safecross.mobile.sensor.ProductionDevicePoseTracker(context),
                        guidanceArbiter = kr.safecross.mobile.guidance.GuidanceArbiter()
                    )
                }

            val activeCrossing = androidx.compose.runtime.remember {
                kr.safecross.mobile.perception.VerifiedCrossingContext(
                    crossingId = "CW-GMC-2026-001",
                    approachBearingDegrees = 0.0f,
                    isFieldVerified = true
                )
            }

            kr.safecross.mobile.ui.screens.crossingassist.CrossingAssistScreen(
                viewModel = crossingAssistViewModel,
                crossingContext = activeCrossing,
                voiceAnnouncer = ttsHelper,
                hapticHelper = hapticHelper,
                onClose = {
                    navController.popBackStack()
                }
            )
        }

        // 6. Settings Screen
        composable(Screen.Settings.route) {
            SettingsScreen(
                viewModel = settingsViewModel,
                voiceAnnouncer = ttsHelper,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
