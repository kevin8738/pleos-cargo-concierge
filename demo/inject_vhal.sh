#!/usr/bin/env bash
# =====================================================================================
#  VIP 조수석 — VHAL injection for the STANDARD Android Automotive emulator
# =====================================================================================
#  Target : AOSP automotive system images (e.g. "Android Automotive 14 (API 34)" / API 33
#           x86_64, userdebug) running the *mock* flavor. The app's AospCarVehicle reads these
#           properties through the public CarPropertyManager API.
#  Not for: Pleos_Connect_v2.1. The Pleos Vehicle Service is a separate app; whether it mirrors
#           the AAOS VHAL is unverified → use demo_broadcast.sh there (works on any emulator).
#
#  Shell syntax (packages/services/Car/service/src/com/android/car/CarShellCommand.java, Android 13/14):
#    cmd car_service inject-vhal-event <propId> [areaId] <value> [-t <delaySec>]  # emits a change event
#    cmd car_service set-property-value <propId> [areaId] <value>                 # writes through VHAL
#    cmd car_service get-property-value <propId> [areaId]
#    cmd car_service emulate-driving-state drive|park|reverse                     # Android 13+ shortcut
#  Read-only sensor properties (speed, gear, steering, occupancy) must use inject-vhal-event;
#  writable ones (door position, HVAC) accept set-property-value.
#
#  Permissions on the stock emulator for a normal (non-priv) app:
#    CAR_SPEED, CAR_POWERTRAIN → dangerous → `./inject_vhal.sh grant`
#    DOOR_POS / PERF_STEERING_ANGLE / SEAT_OCCUPANCY / HVAC_* → signature|privileged → NOT readable
#    unless the APK is installed as a priv-app (see README "known risks"). For those signals use
#    demo_broadcast.sh; the app merges both sources into the same state machine.
#
#  STATUS: syntax checked against AOSP source; NOT executed in the authoring session (no emulator).
# =====================================================================================
set -euo pipefail
ADB=${ADB:-adb}
PKG=ai.vipseat.cargoconcierge

# android.car.VehiclePropertyIds (verified from platforms/android-34/optional/android.car.jar)
PERF_VEHICLE_SPEED=291504647        # float, m/s
GEAR_SELECTION=289408000            # int: PARK=4 REVERSE=2 NEUTRAL=1 DRIVE=8
PERF_STEERING_ANGLE=291504649       # float, degrees (steering wheel)
DOOR_POS=373295872                  # int per door area, 0 = closed
SEAT_OCCUPANCY=356518832            # int: VACANT=1 OCCUPIED=2
HVAC_TEMPERATURE_SET=358614275      # float °C, area = zone bitmask
BRAKE_PEDAL_COMPRESSION_PERCENTAGE=291504910   # float %, API 35+ only (absent on API 34 images)

# Area ids (VehicleAreaDoor / VehicleAreaSeat)
AREA_DOOR_DRIVER=1          # ROW_1_LEFT
AREA_DOOR_PASSENGER=4       # ROW_1_RIGHT
AREA_DOOR_TRUNK=536870912   # REAR (0x20000000)
AREA_SEAT_PASSENGER=4       # SEAT_ROW_1_RIGHT
# Emulator HVAC zones: left = ROW_1_LEFT|ROW_2_LEFT|ROW_2_CENTER (0x31=49), right = ROW_1_RIGHT|ROW_2_RIGHT (0x44=68)
AREA_HVAC_RIGHT=68

inject() { $ADB shell cmd car_service inject-vhal-event "$@"; }
setval() { $ADB shell cmd car_service set-property-value "$@"; }

grant() {
  $ADB shell pm grant $PKG android.car.permission.CAR_SPEED || true
  $ADB shell pm grant $PKG android.car.permission.CAR_POWERTRAIN || true
  $ADB shell pm grant $PKG android.permission.RECORD_AUDIO || true
  echo "granted dangerous permissions (privileged ones cannot be granted with pm grant)"
}
speed()  { inject $PERF_VEHICLE_SPEED 0 "$(awk "BEGIN{printf \"%.3f\", $1/3.6}")"; }   # arg: km/h
gear()   { case "$1" in P|p) v=4;; R|r) v=2;; N|n) v=1;; D|d) v=8;; *) echo "gear P|R|N|D"; exit 1;; esac; inject $GEAR_SELECTION 0 $v; }
steer()  { inject $PERF_STEERING_ANGLE 0 "$1"; }                                        # arg: degrees
brake()  { inject $BRAKE_PEDAL_COMPRESSION_PERCENTAGE 0 "$1"; }                         # arg: percent (API 35+)
door()   { # door passenger|driver|trunk open|closed
  case "$1" in passenger) a=$AREA_DOOR_PASSENGER;; driver) a=$AREA_DOOR_DRIVER;; trunk) a=$AREA_DOOR_TRUNK;; *) echo "door passenger|driver|trunk open|closed"; exit 1;; esac
  case "$2" in open) v=1;; closed|close) v=0;; *) echo "door ... open|closed"; exit 1;; esac
  setval $DOOR_POS $a $v
}
occupied() { case "$1" in true|1) v=2;; *) v=1;; esac; inject $SEAT_OCCUPANCY $AREA_SEAT_PASSENGER $v; }
hvac()   { setval $HVAC_TEMPERATURE_SET $AREA_HVAC_RIGHT "$1"; }                        # arg: °C
status() {
  for p in $GEAR_SELECTION $PERF_VEHICLE_SPEED $PERF_STEERING_ANGLE; do $ADB shell cmd car_service get-property-value $p 0 || true; done
  $ADB shell cmd car_service get-property-value $DOOR_POS $AREA_DOOR_PASSENGER || true
  $ADB shell cmd car_service get-property-value $HVAC_TEMPERATURE_SET $AREA_HVAC_RIGHT || true
}

# Mirrors core/.../mock/DemoScenario.kt (same timestamps) — the cake run.
scenario() {
  gear P; speed 0; occupied false
  sleep 1.5; door passenger open
  sleep 2.5; door passenger closed          # → preset sheet (tap 케이크 within 10 s, or: demo_broadcast.sh preset CAKE)
  sleep 7;   gear D
  sleep 1;   speed 10; sleep 2; speed 17; sleep 2; speed 24; sleep 2; speed 30
  sleep 2;   steer 45                        # 0.13 g → chime
  sleep 3;   steer 0; speed 30
  sleep 3;   steer 100                       # 0.29 g → TTS coaching
  sleep 3;   steer 0; speed 30
  sleep 4;   echo ">> navi curve: use demo_broadcast.sh navi curve 100 (no navi on stock emulator)"
  sleep 2;   speed 27; sleep 2; speed 24; sleep 2; speed 22
  sleep 2;   steer 85                        # 0.13 g at 22 km/h → chime only
  sleep 3;   steer 0; speed 25
  sleep 3;   speed 30; sleep 3; speed 30
  sleep 1;   brake 60 || true; speed 12      # hard brake → TTS coaching
  sleep 1;   speed 0; brake 0 || true
  sleep 4;   speed 10; sleep 3; speed 20; sleep 3; speed 12; sleep 2; speed 6; sleep 2; speed 0
  sleep 2;   gear P
  sleep 2;   door driver open                # → "조수석 케이크 챙기세요" + report
}

case "${1:-help}" in
  grant) grant;;
  speed) speed "$2";;
  gear) gear "$2";;
  steer) steer "$2";;
  brake) brake "$2";;
  door) door "$2" "$3";;
  occupied) occupied "$2";;
  hvac) hvac "$2";;
  status) status;;
  scenario) scenario;;
  *) sed -n '2,30p' "$0"; echo; echo "usage: $0 grant|status|scenario|speed <kph>|gear P|R|N|D|steer <deg>|brake <pct>|door <passenger|driver|trunk> <open|closed>|occupied <true|false>|hvac <°C>";;
esac
