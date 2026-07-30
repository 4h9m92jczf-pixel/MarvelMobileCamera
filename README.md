# Marvel Mobile Camera

Ứng dụng Android dùng điện thoại làm camera phát khẩn cấp, cài trực tiếp bằng APK.

## Chức năng

- **SRT LAN:** điện thoại là Caller, kết nối tới vMix Listener trong cùng mạng LAN.
- **RTMP/RTMPS:** đẩy luồng trực tiếp lên server.
- 1080p25, 1080p30, 1080p60, 4K25 và 4K30.
- Không có 720p, 4K60 hoặc NDI.
- Quét các camera logic/ống kính vật lý mà Android thực sự công khai.
- Chuyển camera trong lúc phát khi thiết bị hỗ trợ.
- H.264/AAC bằng encoder phần cứng, tự reconnect và hiển thị bitrate.

## SRT mặc định

Trên vMix tạo một SRT Listener:

- Port: `10080`
- Latency: `60 ms`

Trên điện thoại chỉ nhập IPv4 của máy vMix, ví dụ `192.168.1.50`.

## Build

Mỗi lần push lên `main`, GitHub Actions tạo APK debug đã ký và tải lên artifact `MarvelMobileCamera-apk`.
