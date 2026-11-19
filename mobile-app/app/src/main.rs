mod session;
mod state;
mod view;

fn main() {
    // Phone targets only. `dx serve --android` / `--ios` compile this for the device.
    #[cfg(all(feature = "mobile", any(target_os = "android", target_os = "ios")))]
    dioxus::launch(view::app);

    #[cfg(not(all(feature = "mobile", any(target_os = "android", target_os = "ios"))))]
    {
        // Keep the screen in the type-check graph on desktop. This binary still
        // refuses to open a window.
        let _screen = view::app;
        eprintln!("Omni mobile runs on Android and iOS.");
        eprintln!("From mobile-app/app: dx serve --android");
        eprintln!("On a Mac with Xcode: dx serve --ios");
        std::process::exit(2);
    }
}
