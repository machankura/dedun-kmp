import SwiftUI
import BackgroundTasks
import UserNotifications
import ComposeApp

/// Registers the background tasks before launch finishes, as BGTaskScheduler requires, and
/// receives notification taps. docs/notifications-and-background-sync.md, Phases 6 and 11.
class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        BackgroundSync.register()
        return true
    }

    /// The dispatcher already decided this should be shown, including that it is not for the
    /// room on screen, so a notification that arrives in front is shown as it would be behind.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        switch response.actionIdentifier {
        case UNNotificationDefaultActionIdentifier:
            PlatformNotifier_iosKt.notificationTapped(userInfo: userInfo)
            completionHandler()
        case UNNotificationDismissActionIdentifier:
            completionHandler()
        default:
            // Mark as read, or Reply with what was typed. Phase 13.
            let text = (response as? UNTextInputNotificationResponse)?.userText
            PlatformNotifier_iosKt.notificationAction(
                actionIdentifier: response.actionIdentifier,
                userInfo: userInfo,
                text: text
            ) {
                completionHandler()
            }
        }
    }
}

/// The two tasks: a quick refresh and a longer processing run, each the Kotlin pass with its own
/// deadline. Rescheduled every time one runs and every time the app leaves the screen.
enum BackgroundSync {
    static let refresh = "mobi.giza.refresh"
    static let processing = "mobi.giza.sync"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: refresh, using: nil) { task in
            handle(task, quick: true)
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: processing, using: nil) { task in
            handle(task, quick: false)
        }
    }

    static func schedule() {
        guard BackgroundSync_iosKt.backgroundSyncEnabled() else { return }

        let refreshRequest = BGAppRefreshTaskRequest(identifier: refresh)
        refreshRequest.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(refreshRequest)

        let processingRequest = BGProcessingTaskRequest(identifier: processing)
        processingRequest.requiresNetworkConnectivity = true
        processingRequest.requiresExternalPower = false
        try? BGTaskScheduler.shared.submit(processingRequest)
    }

    private static func handle(_ task: BGTask, quick: Bool) {
        schedule()
        let running = BackgroundSync_iosKt.startBackgroundSync(quick: quick) { success in
            task.setTaskCompleted(success: success.boolValue)
        }
        task.expirationHandler = {
            running.cancel()
        }
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background {
                BackgroundSync.schedule()
            }
        }
    }
}
