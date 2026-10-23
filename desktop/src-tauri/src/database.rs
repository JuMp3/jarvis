// The bundled PostgreSQL, with pgvector: data in ~/.jarvis/pgdata, its own port, reachable only from this machine,
// with a random password created at the first start and kept in ~/.jarvis/db.password.

use std::fs::{self, File};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use crate::ProcessTree;

/// Not 5432: another PostgreSQL may already run on this machine (5433 is the development one, in Docker).
pub const PORT: u16 = 47322;
pub const USER: &str = "jarvis";
pub const NAME: &str = "jarvis";
const READY_TIMEOUT: Duration = Duration::from_secs(60);

pub struct Database {
    child: Child,
    tree: ProcessTree,
    bin: PathBuf,
    data: PathBuf,
}

/// Starts the server, creating the cluster and the database at the first start. Returns it with the password.
pub fn start(bin: &Path, home: &Path, status: impl Fn(&str)) -> Result<(Database, String), String> {
    let data = home.join("pgdata");
    let password_file = home.join("db.password");
    let first = !data.join("PG_VERSION").is_file();
    if first {
        status("firstDatabase"); // a key of the splash screen, which says it in its language
        write_secret(&password_file, &random_password()?)?;
        // builtin C.UTF-8: the same collation on every operating system, Unicode-aware upper and lower case
        run(tool(bin, "initdb")
            .arg("-D").arg(&data)
            .args(["-U", USER, "-A", "scram-sha-256", "-E", "UTF8", "--locale-provider=builtin", "--builtin-locale=C.UTF-8"])
            .arg(format!("--pwfile={}", password_file.display())), "initdb")?;
    }
    let password = fs::read_to_string(&password_file).map_err(|e| format!("{}: {e}", password_file.display()))?
        .trim().to_string();

    let log = File::create(home.join("logs").join("postgres.log")).map_err(|e| e.to_string())?;
    let mut server = tool(bin, "postgres");
    server.arg("-D").arg(&data)
        .args(["-p", &PORT.to_string(), "-c", "listen_addresses=127.0.0.1"])
        .stdin(Stdio::null())
        .stdout(log.try_clone().map_err(|e| e.to_string())?)
        .stderr(log);
    let child = server.spawn().map_err(|e| format!("postgres: {e}"))?;
    let tree = ProcessTree::adopt(&child)?;
    let mut database = Database { child, tree, bin: bin.to_path_buf(), data };

    let started = Instant::now();
    while !database.ready() {
        if let Ok(Some(exit)) = database.child.try_wait() {
            return Err(format!("il database si è chiuso all'avvio ({exit}): dettagli in ~/.jarvis/logs/postgres.log"));
        }
        if started.elapsed() > READY_TIMEOUT {
            return Err("il database non risponde: dettagli in ~/.jarvis/logs/postgres.log".into());
        }
        thread::sleep(Duration::from_millis(300));
    }
    if first {
        run(database.client("createdb", &password).arg(NAME), "createdb")?;
    }
    Ok((database, password))
}

impl Database {
    fn ready(&self) -> bool {
        tool(&self.bin, "pg_isready")
            .args(["-h", "127.0.0.1", "-p", &PORT.to_string(), "-q"])
            .status().map(|s| s.success()).unwrap_or(false)
    }

    fn client(&self, name: &str, password: &str) -> Command {
        let mut command = tool(&self.bin, name);
        command.args(["-h", "127.0.0.1", "-p", &PORT.to_string(), "-U", USER]).env("PGPASSWORD", password);
        command
    }

    /// A clean shutdown, so the next start needs no recovery; a crash of the shell still ends the server (job).
    pub fn stop(mut self) {
        let _ = tool(&self.bin, "pg_ctl").arg("stop").arg("-D").arg(&self.data).args(["-m", "fast", "-w"]).status();
        self.tree.end();
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

/// A PostgreSQL program of the bundle, without a console window.
fn tool(bin: &Path, name: &str) -> Command {
    let mut command = Command::new(bin.join(name));
    ProcessTree::prepare(&mut command);
    // Linux and macOS: built without an absolute rpath (prepare-postgres.sh), the tools find libpq in lib/
    #[cfg(target_os = "linux")]
    command.env("LD_LIBRARY_PATH", bin.join("..").join("lib"));
    #[cfg(target_os = "macos")]
    command.env("DYLD_LIBRARY_PATH", bin.join("..").join("lib"));
    command
}

fn run(command: &mut Command, what: &str) -> Result<(), String> {
    let output = command.output().map_err(|e| format!("{what}: {e}"))?;
    if output.status.success() {
        return Ok(());
    }
    Err(format!("{what} non riuscito: {}", String::from_utf8_lossy(&output.stderr).trim()))
}

fn random_password() -> Result<String, String> {
    let mut bytes = [0u8; 24];
    getrandom::fill(&mut bytes).map_err(|e| e.to_string())?;
    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

/// Readable by this user only: on Windows the user profile already is; elsewhere 0600.
fn write_secret(path: &Path, secret: &str) -> Result<(), String> {
    fs::write(path, secret).map_err(|e| format!("{}: {e}", path.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(0o600)).map_err(|e| e.to_string())?;
    }
    Ok(())
}
