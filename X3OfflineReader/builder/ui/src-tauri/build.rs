fn main() {
    println!("cargo:rerun-if-env-changed=CARGO_CFG_TARGET_OS");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        panic!("Builder supports Windows only; GTK targets remain blocked pending upstream glib remediation");
    }
    println!("cargo:rerun-if-env-changed=PROFILE");
    println!("cargo:rerun-if-env-changed=CARGO_FEATURE_CUSTOM_PROTOCOL");
    if std::env::var("PROFILE").as_deref() == Ok("release")
        && std::env::var_os("CARGO_FEATURE_CUSTOM_PROTOCOL").is_none()
    {
        panic!("Release builds must embed the UI: use npm run desktop:build or --features custom-protocol");
    }
    tauri_build::build()
}
