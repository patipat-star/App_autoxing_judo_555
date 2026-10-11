# PC Overlay Control

แอป Android (Kotlin) แสดง **ปุ่มลอย** บนหุ่นยนต์ ใช้ควบคุม Zoom และดูภาพกล้องจาก Raspberry Pi 5

## ปุ่ม
| ปุ่ม | หน้าที่ |
|---|---|
| ไอคอนหุ่นยนต์ | ลากย้ายได้ / แตะเพื่อซ่อน-แสดงเมนู |
| 1 (Zoom) | เปิด Zoom แล้วแสดงปุ่ม ✕ แดง กดเพื่อออกจากประชุมอัตโนมัติ |
| 2 (กล้อง) | เปิดภาพสตรีมจาก Pi เต็มจอ + แสดงจำนวนนิ้วมือ |

## สิทธิ์ที่ต้องให้
- **แสดงทับแอปอื่น (Overlay)** — จำเป็น
- Usage Access, การแจ้งเตือน, ยกเว้น Battery Optimization — ขอครั้งเดียว ปฏิเสธได้
- **Accessibility Service** ("Force Leave Zoom Service") — เปิดเองใน ตั้งค่า > การเข้าถึง
  ถ้าไม่เปิด ปุ่ม ✕ จะกลับหน้าเดิมแต่กด Leave ให้ไม่ได้

## ตั้งค่า IP ของ Pi (ซ่อนอยู่)
1. กดปุ่ม 2 เปิดสตรีม
2. **กดค้าง** ที่ตัวเลขจำนวนนิ้วมือมุมซ้ายบน
3. ครั้งแรก: ใส่ IP และตั้ง **PIN เจ้าของ** 4-8 หลัก
   ครั้งต่อไป: ใส่ PIN ก่อน จึงเห็นและแก้ IP ได้ (ใส่ผิด 5 ครั้งล็อก 30 วินาที)
4. ลืม PIN: ล้างข้อมูลแอปในตั้งค่าเครื่อง (IP จะกลับเป็นค่าเริ่มต้น `192.168.100.245`)

## Endpoint ฝั่ง Pi ที่แอปเรียก
| | |
|---|---|
| `GET  http://<pi>:5000/video_feed` | ภาพ MJPEG |
| `POST http://<pi>:5000/api/trigger_button4` | เปิดโหมดติดตามคน (PERSON) |
| `POST http://<pi>:5000/api/stop_button4` | ปิดกล้อง |
| `ws://<pi>:8765` | รับ JSON `{"count": n}` จำนวนนิ้วมือ |

> ชื่อ `button4` เป็นชื่อเดิมฝั่ง Pi ถ้าจะเปลี่ยนต้องแก้ทั้งสองฝั่งพร้อมกัน

## Build / ทดสอบ
ต้องใช้ JDK 21 (ตั้ง `JAVA_HOME` ให้ชี้ไป JDK ที่มี `jlink`)

```
./gradlew :app:assembleDebug     # สร้าง APK
./gradlew :app:testDebugUnitTest # unit test
./gradlew :app:lintDebug         # lint
```

## โครงสร้าง
- `MainActivity` — ไม่มี UI ขอสิทธิ์แล้วเปิด service
- `FloatingService` — ปุ่มลอย สตรีม Pi และตรรกะ Zoom
- `ZoomAccessibilityService` — ตรวจว่า Zoom อยู่หน้าจอ และกด Leave
- `BootReceiver` — เปิด service อัตโนมัติหลังบูต
- `PiSettings` — IP และ PIN ของ Pi (SharedPreferences)
- `TextMatcher` — จับข้อความปุ่มของ Zoom
