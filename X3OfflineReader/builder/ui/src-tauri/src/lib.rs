#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde_json::{json, Value};
use std::{
    fs,
    io::{BufRead, BufReader},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::Mutex,
    time::{SystemTime, UNIX_EPOCH},
};
use tauri::{Emitter, Manager, Runtime, WindowEvent};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

const CREATE_NO_WINDOW: u32 = 0x0800_0000;

struct ActiveBuild {
    control_file: PathBuf,
    request_file: PathBuf,
    close_after_stop: bool,
}

#[derive(Default)]
struct RuntimeState(Mutex<Option<ActiveBuild>>);

fn project_root() -> Result<PathBuf, String> {
    let executable = std::env::current_exe().map_err(|error| error.to_string())?;
    if let Some(parent) = executable.parent() {
        if parent.join("X3BuilderCLI.exe").is_file() {
            return Ok(parent.to_path_buf());
        }
    }
    let current = std::env::current_dir().map_err(|error| error.to_string())?;
    for candidate in current.ancestors() {
        if candidate.join("builder").is_dir() && candidate.join("specification").is_dir() {
            return Ok(candidate.to_path_buf());
        }
    }
    Err("无法定位听读工坊项目或转换核心".into())
}

fn backend_command(root: &Path, args: &[String]) -> Result<Command, String> {
    let packaged = root.join("X3BuilderCLI.exe");
    let mut command = if packaged.is_file() {
        let mut value = Command::new(packaged);
        value.args(args);
        value
    } else {
        let script = root.join("builder/build/install/builder/bin/builder.bat");
        if !script.is_file() {
            return Err("转换核心尚未构建，请先运行 Builder installDist".into());
        }
        let mut value = Command::new("cmd.exe");
        value.args(["/D", "/S", "/C"]).arg(script).args(args);
        value
    };
    command.current_dir(root).stdin(Stdio::null());
    #[cfg(windows)]
    command.creation_flags(CREATE_NO_WINDOW);
    Ok(command)
}

fn capture(args: Vec<String>) -> Result<Value, String> {
    let root = project_root()?;
    let output = backend_command(&root, &args)?
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .output()
        .map_err(|error| format!("无法启动转换核心：{error}"))?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    let line = stdout.lines().rev().find(|line| !line.trim().is_empty()).ok_or_else(|| {
        let diagnostic = String::from_utf8_lossy(&output.stderr);
        format!("转换核心没有返回结果：{}", diagnostic.chars().take(240).collect::<String>())
    })?;
    serde_json::from_str(line).map_err(|error| format!("转换核心返回了无效结果：{error}"))
}

#[tauri::command]
fn bridge_state() -> Result<Value, String> {
    capture(vec!["bridge-state".into()])
}

#[tauri::command]
fn preview_audio(provider: String, voice: String) -> Result<Value, String> {
    capture(vec!["bridge-preview".into(), provider, voice])
}

#[tauri::command]
fn cache_action(action: String) -> Result<Value, String> {
    if !matches!(action.as_str(), "prune" | "stale" | "clear") {
        return Err("未知的缓存操作".into());
    }
    capture(vec!["bridge-cache".into(), action])
}

fn unique_file(directory: &Path, suffix: &str) -> PathBuf {
    let millis = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis();
    directory.join(format!("{}-{millis}-{}.{}", std::process::id(), std::thread::current().name().unwrap_or("ui"), suffix))
}

fn launch_build<R: Runtime>(app: tauri::AppHandle<R>, state: &RuntimeState, mut request: Value, resume: bool) -> Result<(), String> {
    let root = project_root()?;
    let request_root = root.join("builder/.work/ui-requests");
    fs::create_dir_all(&request_root).map_err(|error| format!("无法创建任务目录：{error}"))?;
    let request_file = unique_file(&request_root, "json");
    let control_file = unique_file(&request_root, "stop");
    let object = request.as_object_mut().ok_or("任务参数无效")?;
    object.insert("resume".into(), Value::Bool(resume));
    object.insert("controlFile".into(), Value::String(control_file.to_string_lossy().to_string()));
    fs::write(&request_file, serde_json::to_vec(&request).map_err(|error| error.to_string())?)
        .map_err(|error| format!("无法保存任务请求：{error}"))?;

    let mut active = state.0.lock().map_err(|_| "任务状态不可用")?;
    if active.is_some() {
        return Err("已有转换任务正在运行".into());
    }
    let args = vec!["bridge-build".into(), request_file.to_string_lossy().to_string()];
    let mut child = backend_command(&root, &args)?
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|error| format!("无法启动转换任务：{error}"))?;
    let stdout = child.stdout.take().ok_or("无法读取转换进度")?;
    *active = Some(ActiveBuild { control_file: control_file.clone(), request_file: request_file.clone(), close_after_stop: false });
    drop(active);

    std::thread::Builder::new().name("builder-events".into()).spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            if let Ok(value) = serde_json::from_str::<Value>(&line) {
                let _ = app.emit("builder-event", value);
            }
        }
        let result = child.wait();
        let (close_after, cleanup_request, cleanup_control) = {
            let state = app.state::<RuntimeState>();
            let mut guard = state.0.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            let finished = guard.take();
            finished.map(|task| (task.close_after_stop, task.request_file, task.control_file))
                .unwrap_or((false, request_file, control_file))
        };
        let _ = fs::remove_file(cleanup_request);
        let _ = fs::remove_file(cleanup_control);
        if let Ok(status) = result {
            if !status.success() {
                let _ = app.emit("builder-event", json!({"type":"backend-exit","message":"转换核心已停止"}));
            }
        }
        if close_after {
            if let Some(window) = app.get_webview_window("main") { let _ = window.close(); }
        }
    }).map_err(|error| format!("无法启动进度监听：{error}"))?;
    Ok(())
}

#[tauri::command]
fn start_build(app: tauri::AppHandle, state: tauri::State<'_, RuntimeState>, request: Value) -> Result<(), String> {
    launch_build(app, &state, request, false)
}

#[tauri::command]
fn resume_build(app: tauri::AppHandle, state: tauri::State<'_, RuntimeState>) -> Result<(), String> {
    launch_build(app, &state, json!({}), true)
}

#[tauri::command]
fn pause_build(state: tauri::State<'_, RuntimeState>, close_after: bool) -> Result<(), String> {
    let mut guard = state.0.lock().map_err(|_| "任务状态不可用")?;
    let active = guard.as_mut().ok_or("当前没有正在运行的任务")?;
    active.close_after_stop |= close_after;
    fs::write(&active.control_file, b"pause").map_err(|error| format!("无法发出保存请求：{error}"))
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(RuntimeState::default())
        .invoke_handler(tauri::generate_handler![bridge_state, preview_audio, cache_action, start_build, resume_build, pause_build])
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                let state = window.state::<RuntimeState>();
                let mut guard = state.0.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
                if let Some(active) = guard.as_mut() {
                    api.prevent_close();
                    active.close_after_stop = true;
                    let _ = fs::write(&active.control_file, b"pause");
                    let _ = window.emit("builder-event", json!({"type":"closing","message":"正在保存转换进度，完成后自动关闭…"}));
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("failed to run Tingdu Workshop");
}
