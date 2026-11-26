# Clone và làm việc theo cấu trúc multi-repository

Yêu cầu: Git, PowerShell7.4+, JDK21, Node24/npm và Docker cho integration tests/scanners.
Repo thuộc `vietluccuu`; thành viên dùng GitHub account của mình có quyền đọc repo private.
Không dùng hoặc chia sẻ token/mật khẩu của một thành viên khác.

```powershell
mkdir BaoTin
cd BaoTin
git clone https://github.com/vietluccuu/Infra.git
pwsh -NoProfile -File Infra/Setup-Workspace.ps1 -TargetDirectory (Get-Location).Path
```

Workspace là thư mục cha **không có `.git`**. Mỗi folder con là một repository riêng:

```text
BaoTin/
  BaoTin_Backend_Architecture_Master_Plan.md
  AGENTS.md
  AI/ Frontend/ Analytics-Service/ API-Gateway/ Auth-Service/
  Campaign-Service/ Contracts/ Infra/ Model-Inference-Service/
  Notification-Service/ Report-Service/ Reputation-Service/ Search-Service/ Threat-Service/
```

Setup clone đúng `main`/origin, đặt tài liệu workspace cần cho AGENTS và không ghi đè
repo hoặc tài liệu đã có. Nó không tự pull/reset/clean, không copy `.env`, credential,
private key hoặc artifacts của máy triển khai. Chạy lại với `-SkipClone` để kiểm tra cấu trúc.
Nếu folder đã có nhưng chưa là Git repo hoặc origin khác, reconcile thủ công trước.

Đọc master plan, README/TODO/AGENTS gần nhất trước khi làm task. Backend chạy wrapper của
chính repo (`gradlew.bat test bootJar`); frontend chạy `npm ci`, typecheck/test/build:web.
Scaffolds còn lại chỉ startup; không đóng business TODO bằng mock response.

Pipeline chính: ten backend JARs + Expo web artifact, deploy trực tiếp bằng Jenkins trên
Windows node riêng. `Infra/Jenkinsfile`, `direct/README.md` và `ci/README.md` là tuyến
hiện tại. Docker image là alternative. Không dùng các workspace legacy compose/generator
để overwrite pipeline mới hoặc recreate controller. Secret cấu hình đặt ngoài Git,
điền đường dẫn từ `.env.example` phù hợp; không gửi secret vào chat/commit.

Mỗi người làm branch/PR trong repo được giao. Main và Jenkins job/revision configuration
phải do maintainers bảo vệ. User headers/role/verified flags không thay thế backend
authorization; business service vẫn authorize tại boundary khi được triển khai.
