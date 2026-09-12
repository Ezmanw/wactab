mod protocol;
mod uinput_device;

use anyhow::{Context, Result};
use clap::Parser;
use protocol::{PenEvent, PACKET_LEN};
use std::process::Command;
use std::time::Duration;
use tokio::io::AsyncReadExt;
use tokio::net::TcpStream;
use uinput_device::PenDevice;

/// Wactab daemon: turns an Android tablet into a Linux drawing tablet over USB (adb).
#[derive(Parser, Debug)]
struct Args {
    /// TCP port used for both the host and device side of `adb forward`.
    #[arg(short, long, default_value_t = 7912)]
    port: u16,

    /// Skip running `adb forward` automatically (do it yourself beforehand).
    #[arg(long)]
    no_adb: bool,

    /// Present the virtual device as a tablet (BTN_TOOL_PEN/BTN_STYLUS) with real
    /// pressure/tilt instead of a plain pointer. Requires compositor zwp_tablet_v2
    /// support to actually receive events in apps; see README Status section.
    #[arg(long)]
    tablet_mode: bool,
}

fn setup_adb_forward(port: u16) -> Result<()> {
    let status = Command::new("adb")
        .args(["forward", &format!("tcp:{port}"), &format!("tcp:{port}")])
        .status()
        .context("failed to run `adb`; is it installed and on PATH?")?;
    if !status.success() {
        anyhow::bail!("`adb forward` failed (is a device connected with USB debugging enabled?)");
    }
    Ok(())
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();

    if !args.no_adb {
        setup_adb_forward(args.port)?;
        println!("adb forward tcp:{0} tcp:{0} set up", args.port);
    }

    let mut pen = PenDevice::new(args.tablet_mode).context("failed to create uinput virtual device (are you in the `input` group / running as root?)")?;
    println!("Virtual pen device created. Waiting for the Wactab Android app to connect...");

    loop {
        match TcpStream::connect(("127.0.0.1", args.port)).await {
            Ok(mut stream) => {
                println!("Connected to Wactab app.");
                stream.set_nodelay(true).ok();
                let mut buf = [0u8; PACKET_LEN];
                loop {
                    match stream.read_exact(&mut buf).await {
                        Ok(_) => match PenEvent::decode(&buf) {
                            Ok(ev) => {
                                if let Err(e) = pen.apply(&ev) {
                                    eprintln!("failed to emit event: {e}");
                                }
                            }
                            Err(e) => eprintln!("bad packet: {e}"),
                        },
                        Err(_) => {
                            println!("Disconnected. Reconnecting...");
                            break;
                        }
                    }
                }
            }
            Err(_) => {
                tokio::time::sleep(Duration::from_millis(500)).await;
            }
        }
    }
}
