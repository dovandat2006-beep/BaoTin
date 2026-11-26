# Included inline in the existing dockerd entrypoint; never sends a process signal.
_baotin_clear_stale_containerd_pid() {
    local pid_file="$1" proc_root="$2" pid process
    if [ -L "$pid_file" ]; then
        echo 'BAOTIN_CONTAINERD_PID rejected=nonregular_path' >&2; return 1
    fi
    [ -e "$pid_file" ] || return 0
    if [ ! -f "$pid_file" ]; then
        echo 'BAOTIN_CONTAINERD_PID rejected=nonregular_path' >&2; return 1
    fi
    pid=$(cat "$pid_file") || return 1
    case "$pid" in ''|*[!0-9]*) echo 'BAOTIN_CONTAINERD_PID rejected=malformed_pid' >&2; return 1;; esac
    process=''
    if [ -e "$proc_root/$pid/comm" ]; then
        process=$(cat "$proc_root/$pid/comm") || return 1
    fi
    [ "$process" != containerd ] || return 0
    # Recheck the file so a replacement PID is not removed after inspection.
    [ "$(cat "$pid_file")" = "$pid" ] || return 1
    rm -- "$pid_file" || return 1
    printf 'BAOTIN_CONTAINERD_PID stale_removed=true pid=%s signal_sent=false\n' "$pid"
}
