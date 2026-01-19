package com.flockyou.data.model

import java.util.UUID

/**
 * Database of known surveillance device signatures
 * Based on data from deflock.me, GainSec research, and FCC filings
 * 
 * Detection methodology:
 * - WiFi: SSID patterns, MAC OUI prefixes from LTE modems
 * - BLE: Device names, Service UUIDs (especially for Raven)
 * 
 * Flock cameras use cellular LTE modems (Quectel, Telit, Sierra Wireless)
 * and emit WiFi for configuration/management
 */
object DetectionPatterns {
    
    // ==================== SSID Patterns ====================
    // Primary detection method - Flock cameras advertise specific SSIDs
    val ssidPatterns = listOf(
        // Flock Safety - Primary patterns
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^flock[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 95,
            description = "Flock Safety ALPR Camera - captures license plates and vehicle characteristics"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^fs[_-].*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Safety Camera (FS prefix variant)"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^falcon[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Falcon ALPR - standard pole-mounted camera"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^sparrow[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Sparrow ALPR - compact camera model"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^condor[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Condor ALPR - high-speed multi-lane camera"
        ),
        
        // Penguin surveillance
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^penguin[_-]?.*",
            deviceType = DeviceType.PENGUIN_SURVEILLANCE,
            manufacturer = "Penguin",
            threatScore = 85,
            description = "Penguin Surveillance Device - mobile ALPR system"
        ),
        
        // Pigvision
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^pigvision[_-]?.*",
            deviceType = DeviceType.PIGVISION_SYSTEM,
            manufacturer = "Pigvision",
            threatScore = 85,
            description = "Pigvision Surveillance System"
        ),
        
        // Generic ALPR patterns
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^alpr[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = null,
            threatScore = 80,
            description = "Generic ALPR System - Automated License Plate Reader"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^lpr[_-]?cam.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = null,
            threatScore = 75,
            description = "License Plate Reader Camera"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^vigilant[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = "Motorola Solutions",
            threatScore = 85,
            description = "Vigilant ALPR (Motorola) - competitor to Flock"
        ),
        
        // ==================== Police Technology Patterns ====================
        
        // Motorola Solutions
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^moto[_-]?(body|cam|radio|apx).*",
            deviceType = DeviceType.MOTOROLA_POLICE_TECH,
            manufacturer = "Motorola Solutions",
            threatScore = 80,
            description = "Motorola police equipment (body camera, radio, APX)"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^apx[_-]?.*",
            deviceType = DeviceType.POLICE_RADIO,
            manufacturer = "Motorola Solutions",
            threatScore = 75,
            description = "Motorola APX Radio System"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^astro[_-]?.*",
            deviceType = DeviceType.POLICE_RADIO,
            manufacturer = "Motorola Solutions",
            threatScore = 70,
            description = "Motorola ASTRO Radio System"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^v[_-]?[35]00[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Motorola Solutions",
            threatScore = 80,
            description = "Motorola V300/V500 Body Camera"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^watchguard[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Motorola Solutions (WatchGuard)",
            threatScore = 80,
            description = "WatchGuard Body/Dash Camera System"
        ),
        
        // Axon (formerly TASER)
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^axon[_-]?.*",
            deviceType = DeviceType.AXON_POLICE_TECH,
            manufacturer = "Axon Enterprise",
            threatScore = 85,
            description = "Axon police equipment (body camera, TASER, etc.)"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(axon[_-]?)?(body|flex)[_-]?[234]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Axon Enterprise",
            threatScore = 80,
            description = "Axon Body Camera (Body 2/3/4, Flex)"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^taser[_-]?.*",
            deviceType = DeviceType.AXON_POLICE_TECH,
            manufacturer = "Axon Enterprise",
            threatScore = 75,
            description = "TASER device with connectivity"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^evidence[_-]?.*",
            deviceType = DeviceType.AXON_POLICE_TECH,
            manufacturer = "Axon Enterprise",
            threatScore = 70,
            description = "Axon Evidence.com sync device"
        ),
        
        // L3Harris
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^l3harris[_-]?.*",
            deviceType = DeviceType.L3HARRIS_SURVEILLANCE,
            manufacturer = "L3Harris Technologies",
            threatScore = 85,
            description = "L3Harris surveillance/communications equipment"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^stingray[_-]?.*",
            deviceType = DeviceType.STINGRAY_IMSI,
            manufacturer = "L3Harris Technologies",
            threatScore = 100,
            description = "StingRay Cell Site Simulator (IMSI Catcher)"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(hail|king|queen)storm[_-]?.*",
            deviceType = DeviceType.STINGRAY_IMSI,
            manufacturer = "L3Harris Technologies",
            threatScore = 100,
            description = "Hailstorm/Kingfish Cell Site Simulator"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(harris|xg)[_-]?[0-9]+.*",
            deviceType = DeviceType.POLICE_RADIO,
            manufacturer = "L3Harris Technologies",
            threatScore = 70,
            description = "Harris XG Radio System"
        ),
        
        // Digital Ally
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^digital[_-]?ally[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Digital Ally",
            threatScore = 75,
            description = "Digital Ally Body/Dash Camera"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^firstvu[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Digital Ally",
            threatScore = 75,
            description = "Digital Ally FirstVU Body Camera"
        ),
        
        // Cellebrite
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^cellebrite[_-]?.*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = "Cellebrite",
            threatScore = 90,
            description = "Cellebrite mobile forensics device"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^ufed[_-]?.*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = "Cellebrite",
            threatScore = 90,
            description = "Cellebrite UFED (Universal Forensic Extraction Device)"
        ),
        
        // Graykey/Magnet
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^graykey[_-]?.*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = "Grayshift",
            threatScore = 90,
            description = "GrayKey iPhone forensics device"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^magnet[_-]?forensic.*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = "Magnet Forensics",
            threatScore = 85,
            description = "Magnet Forensics device"
        ),
        
        // Genetec
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^genetec[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = "Genetec",
            threatScore = 80,
            description = "Genetec Security Center / AutoVu ALPR"
        ),
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^autovu[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = "Genetec",
            threatScore = 85,
            description = "Genetec AutoVu ALPR System"
        ),
        
        // Getac (ruggedized police computers)
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^getac[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = "Getac",
            threatScore = 60,
            description = "Getac ruggedized computer (often used in patrol vehicles)"
        ),
        
        // Panasonic Toughbook (common in police vehicles)
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^toughbook[_-]?.*",
            deviceType = DeviceType.UNKNOWN_SURVEILLANCE,
            manufacturer = "Panasonic",
            threatScore = 55,
            description = "Panasonic Toughbook (commonly used by law enforcement)"
        ),

        // ==================== Smart Home / IoT Surveillance Patterns ====================

        // Ring Doorbells
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^ring[_-]?(doorbell|cam|setup|stick).*",
            deviceType = DeviceType.RING_DOORBELL,
            manufacturer = "Ring (Amazon)",
            threatScore = 40,
            description = "Ring doorbell/camera - shares footage with 2,500+ law enforcement agencies"
        ),

        // Nest/Google Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(nest|google)[_-]?(cam|doorbell|hello).*",
            deviceType = DeviceType.NEST_CAMERA,
            manufacturer = "Google/Nest",
            threatScore = 35,
            description = "Nest/Google camera - cloud-connected home surveillance"
        ),

        // Amazon Sidewalk
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(amazon[_-]?sidewalk|sidewalk[_-]?bridge).*",
            deviceType = DeviceType.AMAZON_SIDEWALK,
            manufacturer = "Amazon",
            threatScore = 45,
            description = "Amazon Sidewalk mesh network - shares bandwidth with neighbors/Amazon"
        ),

        // Wyze Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^wyze[_-]?(cam|doorbell|setup).*",
            deviceType = DeviceType.WYZE_CAMERA,
            manufacturer = "Wyze",
            threatScore = 35,
            description = "Wyze camera - budget smart home camera"
        ),

        // Arlo Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^arlo[_-]?(cam|pro|ultra|setup).*",
            deviceType = DeviceType.ARLO_CAMERA,
            manufacturer = "Arlo",
            threatScore = 35,
            description = "Arlo security camera"
        ),

        // Eufy/Anker Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^eufy[_-]?(cam|doorbell|security).*",
            deviceType = DeviceType.EUFY_CAMERA,
            manufacturer = "Eufy/Anker",
            threatScore = 35,
            description = "Eufy security camera"
        ),

        // Blink Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^blink[_-]?(cam|mini|setup).*",
            deviceType = DeviceType.BLINK_CAMERA,
            manufacturer = "Blink (Amazon)",
            threatScore = 40,
            description = "Blink camera (Amazon) - cloud-connected surveillance"
        ),

        // SimpliSafe
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^simplisafe[_-]?.*",
            deviceType = DeviceType.SIMPLISAFE_DEVICE,
            manufacturer = "SimpliSafe",
            threatScore = 35,
            description = "SimpliSafe security system"
        ),

        // ADT Security
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^adt[_-]?(pulse|cam|security).*",
            deviceType = DeviceType.ADT_DEVICE,
            manufacturer = "ADT",
            threatScore = 40,
            description = "ADT security system - may share with law enforcement"
        ),

        // Vivint
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^vivint[_-]?.*",
            deviceType = DeviceType.VIVINT_DEVICE,
            manufacturer = "Vivint",
            threatScore = 40,
            description = "Vivint smart home security"
        ),

        // ==================== Traffic Enforcement Patterns ====================

        // Speed Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(speed[_-]?cam|redflex|verra|xerox[_-]?ats).*",
            deviceType = DeviceType.SPEED_CAMERA,
            manufacturer = null,
            threatScore = 70,
            description = "Speed enforcement camera"
        ),

        // Red Light Cameras
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(red[_-]?light|intersection[_-]?cam|ats[_-]?).*",
            deviceType = DeviceType.RED_LIGHT_CAMERA,
            manufacturer = null,
            threatScore = 65,
            description = "Red light enforcement camera"
        ),

        // Toll Systems
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(ezpass|sunpass|fastrak|toll[_-]?gantry).*",
            deviceType = DeviceType.TOLL_READER,
            manufacturer = null,
            threatScore = 50,
            description = "Electronic toll collection system"
        ),

        // ==================== Network Attack/Pentest Device Patterns ====================

        // WiFi Pineapple
        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(pineapple|hak5|wifi[_-]?pineapple).*",
            deviceType = DeviceType.WIFI_PINEAPPLE,
            manufacturer = "Hak5",
            threatScore = 90,
            description = "WiFi Pineapple - network auditing/attack tool"
        ),

        // ==================== Retail/Commercial Tracking Patterns ====================

        DetectionPattern(
            type = PatternType.SSID_REGEX,
            pattern = "(?i)^(retailnext|shoppertrak|footfall).*",
            deviceType = DeviceType.CROWD_ANALYTICS,
            manufacturer = null,
            threatScore = 50,
            description = "Retail foot traffic analytics system"
        )
    )
    
    // ==================== BLE Device Name Patterns ====================
    val bleNamePatterns = listOf(
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^flock[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 95,
            description = "Flock Safety BLE Configuration Interface"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^falcon[_-]?.*",
            deviceType = DeviceType.FLOCK_SAFETY_CAMERA,
            manufacturer = "Flock Safety",
            threatScore = 90,
            description = "Flock Falcon Camera BLE"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^raven[_-]?.*",
            deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
            manufacturer = "Flock Safety / SoundThinking",
            threatScore = 100,
            description = "Raven Acoustic Gunshot Detector - listens for gunfire and 'human distress'"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^shotspotter[_-]?.*",
            deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
            manufacturer = "SoundThinking (formerly ShotSpotter)",
            threatScore = 100,
            description = "ShotSpotter Acoustic Sensor - gunfire detection system"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^soundthinking[_-]?.*",
            deviceType = DeviceType.RAVEN_GUNSHOT_DETECTOR,
            manufacturer = "SoundThinking",
            threatScore = 100,
            description = "SoundThinking Acoustic Surveillance Device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^penguin[_-]?.*",
            deviceType = DeviceType.PENGUIN_SURVEILLANCE,
            manufacturer = "Penguin",
            threatScore = 85,
            description = "Penguin BLE Device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^pigvision[_-]?.*",
            deviceType = DeviceType.PIGVISION_SYSTEM,
            manufacturer = "Pigvision",
            threatScore = 85,
            description = "Pigvision BLE Device"
        ),
        
        // ==================== Police Technology BLE Patterns ====================
        
        // Axon Body Cameras
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^axon[_-]?.*",
            deviceType = DeviceType.AXON_POLICE_TECH,
            manufacturer = "Axon Enterprise",
            threatScore = 80,
            description = "Axon device (body camera, TASER, etc.)"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(body|flex)[_-]?[234]?[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Axon Enterprise",
            threatScore = 80,
            description = "Axon Body Camera"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^ab[234][_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Axon Enterprise",
            threatScore = 80,
            description = "Axon Body Camera (AB2/AB3/AB4)"
        ),
        
        // Motorola Body Cameras
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(moto|si)[_-]?[v][_-]?[0-9]+.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Motorola Solutions",
            threatScore = 80,
            description = "Motorola Body Camera"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^watchguard[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Motorola Solutions (WatchGuard)",
            threatScore = 80,
            description = "WatchGuard Body/Dash Camera"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^apx[_-]?.*",
            deviceType = DeviceType.POLICE_RADIO,
            manufacturer = "Motorola Solutions",
            threatScore = 70,
            description = "Motorola APX Radio"
        ),
        
        // Digital Ally
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(da|firstvu)[_-]?.*",
            deviceType = DeviceType.BODY_CAMERA,
            manufacturer = "Digital Ally",
            threatScore = 75,
            description = "Digital Ally Body Camera"
        ),
        
        // L3Harris
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(l3|harris|l3harris)[_-]?.*",
            deviceType = DeviceType.L3HARRIS_SURVEILLANCE,
            manufacturer = "L3Harris Technologies",
            threatScore = 80,
            description = "L3Harris equipment"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^xg[_-]?[0-9]+.*",
            deviceType = DeviceType.POLICE_RADIO,
            manufacturer = "L3Harris Technologies",
            threatScore = 70,
            description = "L3Harris XG Radio"
        ),
        
        // Cellebrite / Forensics
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(cellebrite|ufed)[_-]?.*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = "Cellebrite",
            threatScore = 95,
            description = "Cellebrite forensics device"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^graykey[_-]?.*",
            deviceType = DeviceType.CELLEBRITE_FORENSICS,
            manufacturer = "Grayshift",
            threatScore = 95,
            description = "GrayKey forensics device"
        ),

        // ==================== Whelen Lightbar / Emergency Vehicle Patterns ====================

        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^cencom[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Whelen Engineering",
            threatScore = 90,
            description = "Whelen CenCom Lightbar Controller - police/emergency vehicle lightbar sync"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^wecan[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Whelen Engineering",
            threatScore = 90,
            description = "Whelen WeCAN Network - vehicle lighting controller"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^whelen[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Whelen Engineering",
            threatScore = 85,
            description = "Whelen emergency lighting equipment"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^core[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Whelen Engineering",
            threatScore = 80,
            description = "Whelen Core lightbar system"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(ion|legacy|liberty|freedom)[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Whelen Engineering",
            threatScore = 75,
            description = "Whelen lightbar series"
        ),

        // ==================== Axon Signal / Body Camera Trigger Patterns ====================

        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^signal[_-]?(sidearm|vehicle|performance)?.*",
            deviceType = DeviceType.AXON_POLICE_TECH,
            manufacturer = "Axon Enterprise",
            threatScore = 90,
            description = "Axon Signal - auto-activates body cameras on siren/gun draw"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^fleet[_-]?[23]?.*",
            deviceType = DeviceType.AXON_POLICE_TECH,
            manufacturer = "Axon Enterprise",
            threatScore = 85,
            description = "Axon Fleet in-car camera system"
        ),

        // ==================== Federal Signal Patterns ====================

        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^federal[_-]?signal.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Federal Signal",
            threatScore = 85,
            description = "Federal Signal emergency lighting"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(valor|integrity|allegiant)[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Federal Signal",
            threatScore = 80,
            description = "Federal Signal lightbar"
        ),

        // ==================== Code 3 / SoundOff Signal Patterns ====================

        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^code[_-]?3.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "Code 3",
            threatScore = 80,
            description = "Code 3 emergency lighting"
        ),
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^soundoff[_-]?.*",
            deviceType = DeviceType.POLICE_VEHICLE,
            manufacturer = "SoundOff Signal",
            threatScore = 80,
            description = "SoundOff Signal emergency equipment"
        ),

        // ==================== Tracker/AirTag Detection Patterns ====================

        // Apple AirTag
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(airtag|find[_-]?my).*",
            deviceType = DeviceType.AIRTAG,
            manufacturer = "Apple",
            threatScore = 60,
            description = "Apple AirTag - potential tracking device"
        ),

        // Tile Trackers
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^tile[_-]?(mate|pro|slim|sticker)?.*",
            deviceType = DeviceType.TILE_TRACKER,
            manufacturer = "Tile",
            threatScore = 55,
            description = "Tile Bluetooth tracker"
        ),

        // Samsung SmartTag
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(smart[_-]?tag|galaxy[_-]?tag).*",
            deviceType = DeviceType.SAMSUNG_SMARTTAG,
            manufacturer = "Samsung",
            threatScore = 55,
            description = "Samsung SmartTag tracker"
        ),

        // Generic BLE Trackers
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(chipolo|nut[_-]?find|pebblebee|cube[_-]?tracker).*",
            deviceType = DeviceType.GENERIC_BLE_TRACKER,
            manufacturer = null,
            threatScore = 50,
            description = "Generic Bluetooth tracker device"
        ),

        // ==================== Smart Home IoT BLE Patterns ====================

        // Ring
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^ring[_-]?(doorbell|cam|chime|setup).*",
            deviceType = DeviceType.RING_DOORBELL,
            manufacturer = "Ring (Amazon)",
            threatScore = 40,
            description = "Ring doorbell/camera BLE setup"
        ),

        // Nest/Google
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(nest|google)[_-]?(cam|doorbell|hello|hub).*",
            deviceType = DeviceType.NEST_CAMERA,
            manufacturer = "Google/Nest",
            threatScore = 35,
            description = "Google Nest camera/doorbell"
        ),

        // Wyze
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^wyze[_-]?.*",
            deviceType = DeviceType.WYZE_CAMERA,
            manufacturer = "Wyze",
            threatScore = 35,
            description = "Wyze smart home device"
        ),

        // Arlo
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^arlo[_-]?.*",
            deviceType = DeviceType.ARLO_CAMERA,
            manufacturer = "Arlo",
            threatScore = 35,
            description = "Arlo security camera"
        ),

        // Eufy
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^eufy[_-]?.*",
            deviceType = DeviceType.EUFY_CAMERA,
            manufacturer = "Eufy/Anker",
            threatScore = 35,
            description = "Eufy security device"
        ),

        // Blink
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^blink[_-]?.*",
            deviceType = DeviceType.BLINK_CAMERA,
            manufacturer = "Blink (Amazon)",
            threatScore = 40,
            description = "Blink camera"
        ),

        // ==================== Retail Beacon Patterns ====================

        // iBeacon / Eddystone patterns
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(ibeacon|eddystone|estimote|kontakt).*",
            deviceType = DeviceType.BLUETOOTH_BEACON,
            manufacturer = null,
            threatScore = 45,
            description = "Retail/location Bluetooth beacon"
        ),

        // Retail analytics
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(retailnext|shoppertrak|sensoro).*",
            deviceType = DeviceType.RETAIL_TRACKER,
            manufacturer = null,
            threatScore = 50,
            description = "Retail foot traffic sensor"
        ),

        // ==================== Law Enforcement Specific BLE Patterns ====================

        // ShotSpotter/Acoustic
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^(shotspot|soundthink|acoustic[_-]?sens).*",
            deviceType = DeviceType.SHOTSPOTTER,
            manufacturer = "SoundThinking",
            threatScore = 95,
            description = "ShotSpotter acoustic gunshot sensor"
        ),

        // GrayKey
        DetectionPattern(
            type = PatternType.BLE_NAME_REGEX,
            pattern = "(?i)^graykey.*",
            deviceType = DeviceType.GRAYKEY_DEVICE,
            manufacturer = "Grayshift",
            threatScore = 95,
            description = "GrayKey mobile forensics device"
        )
    )
    
    // ==================== Raven Service UUIDs ====================
    // Based on GainSec research - raven_configurations.json
    // Firmware versions 1.1.7, 1.2.0, 1.3.1
    data class RavenServiceInfo(
        val uuid: UUID,
        val name: String,
        val description: String,
        val dataExposed: String,
        val firmwareVersions: List<String>
    )
    
    val ravenServiceUuids = listOf(
        RavenServiceInfo(
            uuid = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb"),
            name = "Device Information",
            description = "Standard BLE Device Information Service",
            dataExposed = "Serial number, model number, firmware version, manufacturer",
            firmwareVersions = listOf("1.1.x", "1.2.x", "1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003100-0000-1000-8000-00805f9b34fb"),
            name = "GPS Location",
            description = "Real-time GPS coordinates of the device",
            dataExposed = "Latitude, longitude, altitude, GPS fix status",
            firmwareVersions = listOf("1.2.x", "1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003200-0000-1000-8000-00805f9b34fb"),
            name = "Power Management",
            description = "Battery and solar panel status",
            dataExposed = "Battery level, charging status, solar input voltage",
            firmwareVersions = listOf("1.2.x", "1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003300-0000-1000-8000-00805f9b34fb"),
            name = "Network Status",
            description = "Cellular and WiFi connectivity info",
            dataExposed = "LTE signal strength, carrier, data usage, WiFi status",
            firmwareVersions = listOf("1.2.x", "1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003400-0000-1000-8000-00805f9b34fb"),
            name = "Upload Statistics",
            description = "Data transmission metrics",
            dataExposed = "Bytes uploaded, detection count, last upload time",
            firmwareVersions = listOf("1.3.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00003500-0000-1000-8000-00805f9b34fb"),
            name = "Error/Diagnostics",
            description = "System diagnostics and error logs",
            dataExposed = "Error codes, system health, diagnostic data",
            firmwareVersions = listOf("1.3.x")
        ),
        // Legacy services (firmware 1.1.x)
        RavenServiceInfo(
            uuid = UUID.fromString("00001809-0000-1000-8000-00805f9b34fb"),
            name = "Health Thermometer (Legacy)",
            description = "Repurposed standard BLE service",
            dataExposed = "Device temperature, environmental data",
            firmwareVersions = listOf("1.1.x")
        ),
        RavenServiceInfo(
            uuid = UUID.fromString("00001819-0000-1000-8000-00805f9b34fb"),
            name = "Location/Navigation (Legacy)",
            description = "Repurposed standard BLE location service",
            dataExposed = "Basic location data",
            firmwareVersions = listOf("1.1.x")
        )
    )
    
    val ravenServiceUuidSet: Set<UUID> = ravenServiceUuids.map { it.uuid }.toSet()
    
    /**
     * Estimate Raven firmware version based on advertised services
     */
    fun estimateRavenFirmwareVersion(serviceUuids: List<UUID>): String {
        val hasLegacyHealth = serviceUuids.contains(UUID.fromString("00001809-0000-1000-8000-00805f9b34fb"))
        val hasLegacyLocation = serviceUuids.contains(UUID.fromString("00001819-0000-1000-8000-00805f9b34fb"))
        val hasGps = serviceUuids.contains(UUID.fromString("00003100-0000-1000-8000-00805f9b34fb"))
        val hasPower = serviceUuids.contains(UUID.fromString("00003200-0000-1000-8000-00805f9b34fb"))
        val hasUpload = serviceUuids.contains(UUID.fromString("00003400-0000-1000-8000-00805f9b34fb"))
        val hasError = serviceUuids.contains(UUID.fromString("00003500-0000-1000-8000-00805f9b34fb"))
        
        return when {
            hasUpload || hasError -> "1.3.x (Latest - Full diagnostics)"
            hasGps && hasPower -> "1.2.x (GPS + Power monitoring)"
            hasLegacyHealth || hasLegacyLocation -> "1.1.x (Legacy firmware)"
            else -> "Unknown version"
        }
    }
    
    // ==================== MAC Address Prefixes (OUI) ====================
    // LTE modems commonly used in surveillance equipment
    // Flock uses cellular connectivity - these are modem manufacturer OUIs
    val macPrefixes = listOf(
        // Quectel - Common LTE modem manufacturer
        MacPrefix("50:29:4D", DeviceType.FLOCK_SAFETY_CAMERA, "Quectel (LTE Modem)", 70,
            "Quectel LTE modem - commonly used in Flock cameras"),
        MacPrefix("86:25:19", DeviceType.FLOCK_SAFETY_CAMERA, "Quectel (LTE Modem)", 70,
            "Quectel cellular module"),

        // Telit - Another common IoT/LTE modem maker
        MacPrefix("00:14:2D", DeviceType.UNKNOWN_SURVEILLANCE, "Telit (LTE Modem)", 65,
            "Telit cellular modem - used in IoT surveillance"),
        MacPrefix("D8:C7:71", DeviceType.UNKNOWN_SURVEILLANCE, "Telit Wireless", 65,
            "Telit wireless module"),

        // ==================== Fleet Vehicle OUIs (Police Car WiFi APs) ====================
        // Sierra Wireless - Common in police/fleet vehicles
        MacPrefix("00:0E:8E", DeviceType.FLEET_VEHICLE, "Sierra Wireless", 85,
            "Sierra Wireless - common in police/fleet vehicle mobile routers"),
        MacPrefix("00:11:75", DeviceType.FLEET_VEHICLE, "Sierra Wireless", 85,
            "Sierra Wireless - fleet vehicle mobile hotspot"),
        MacPrefix("00:14:3E", DeviceType.FLEET_VEHICLE, "Sierra Wireless", 80,
            "Sierra Wireless modem - IoT/M2M/fleet applications"),
        MacPrefix("00:A0:D5", DeviceType.FLEET_VEHICLE, "Sierra Wireless", 80,
            "Sierra Wireless cellular module"),

        // Cradlepoint - Popular for mobile command/surveillance vehicles
        MacPrefix("00:30:44", DeviceType.FLEET_VEHICLE, "Cradlepoint", 85,
            "Cradlepoint - mobile router common in police/emergency vehicles"),
        MacPrefix("00:10:8B", DeviceType.FLEET_VEHICLE, "Cradlepoint", 85,
            "Cradlepoint router - often used for mobile surveillance/command"),
        MacPrefix("EC:F4:51", DeviceType.FLEET_VEHICLE, "Cradlepoint", 80,
            "Cradlepoint NetCloud - fleet management router"),

        // Digi International - Fleet telematics
        MacPrefix("00:40:9D", DeviceType.FLEET_VEHICLE, "Digi International", 75,
            "Digi - fleet telematics and mobile connectivity"),

        // CalAmp - Vehicle tracking and fleet management
        MacPrefix("00:07:F9", DeviceType.FLEET_VEHICLE, "CalAmp", 80,
            "CalAmp - vehicle tracking and fleet management"),

        // Geotab - Fleet telematics
        MacPrefix("00:1E:C0", DeviceType.FLEET_VEHICLE, "Geotab", 75,
            "Geotab - fleet telematics device"),

        // u-blox - GPS/cellular modules
        MacPrefix("D4:CA:6E", DeviceType.UNKNOWN_SURVEILLANCE, "u-blox", 60,
            "u-blox cellular/GPS module"),

        // Raspberry Pi (used in DIY/prototype ALPR)
        MacPrefix("B8:27:EB", DeviceType.UNKNOWN_SURVEILLANCE, "Raspberry Pi", 50,
            "Raspberry Pi - potential DIY ALPR system"),
        MacPrefix("DC:A6:32", DeviceType.UNKNOWN_SURVEILLANCE, "Raspberry Pi", 50,
            "Raspberry Pi 4 - potential DIY surveillance"),
        MacPrefix("E4:5F:01", DeviceType.UNKNOWN_SURVEILLANCE, "Raspberry Pi", 50,
            "Raspberry Pi - IoT device"),

        // ==================== Axon / Body Camera Manufacturer OUIs ====================
        // Nordic Semiconductor - used in Axon body cameras and Signal devices
        MacPrefix("00:59", DeviceType.AXON_POLICE_TECH, "Nordic Semiconductor", 75,
            "Nordic Semiconductor - common in Axon body cameras/Signal triggers"),
        MacPrefix("C0:A5:3E", DeviceType.AXON_POLICE_TECH, "Nordic Semiconductor", 75,
            "Nordic Semiconductor BLE - Axon equipment"),
        MacPrefix("F0:5C:D5", DeviceType.AXON_POLICE_TECH, "Nordic Semiconductor", 70,
            "Nordic Semiconductor - police equipment BLE"),

        // Texas Instruments - used in some body cameras
        MacPrefix("D0:39:72", DeviceType.BODY_CAMERA, "Texas Instruments", 65,
            "TI BLE module - potential body camera"),

        // Dialog Semiconductor - BLE chips in wearables/cameras
        MacPrefix("80:EA:CA", DeviceType.BODY_CAMERA, "Dialog Semiconductor", 60,
            "Dialog Semiconductor - BLE wearable/camera")
    )
    
    data class MacPrefix(
        val prefix: String,
        val deviceType: DeviceType,
        val manufacturer: String,
        val threatScore: Int,
        val description: String = ""
    )
    
    /**
     * Check if a MAC address matches any known prefix
     */
    fun matchMacPrefix(macAddress: String): MacPrefix? {
        val normalizedMac = macAddress.uppercase().replace("-", ":")
        return macPrefixes.find { normalizedMac.startsWith(it.prefix.uppercase()) }
    }
    
    /**
     * Check if SSID matches any known pattern
     */
    fun matchSsidPattern(ssid: String): DetectionPattern? {
        return ssidPatterns.find { pattern ->
            try {
                Regex(pattern.pattern).matches(ssid)
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * Check if BLE device name matches any known pattern
     */
    fun matchBleNamePattern(deviceName: String): DetectionPattern? {
        return bleNamePatterns.find { pattern ->
            try {
                Regex(pattern.pattern).matches(deviceName)
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * Check if any service UUIDs match Raven patterns
     */
    fun matchRavenServices(serviceUuids: List<UUID>): List<RavenServiceInfo> {
        return ravenServiceUuids.filter { it.uuid in serviceUuids }
    }
    
    /**
     * Check if this is a Raven device based on service UUIDs
     */
    fun isRavenDevice(serviceUuids: List<UUID>): Boolean {
        // Need at least 2 Raven-specific services to confirm
        val matchCount = serviceUuids.count { it in ravenServiceUuidSet }
        return matchCount >= 2
    }
    
    /**
     * Get detailed info about a device type
     */
    fun getDeviceTypeInfo(deviceType: DeviceType): DeviceTypeInfo {
        return when (deviceType) {
            DeviceType.FLOCK_SAFETY_CAMERA -> DeviceTypeInfo(
                name = "Flock Safety ALPR Camera",
                shortDescription = "Automated License Plate Reader",
                fullDescription = "Flock Safety cameras capture images of vehicles and license plates. " +
                    "They use 'Vehicle Fingerprint' technology to identify make, model, color, and " +
                    "distinguishing features. Data is stored for 30 days and shared with law enforcement. " +
                    "Over 5,000 communities use Flock with 20+ billion monthly plate scans.",
                capabilities = listOf(
                    "License plate capture (up to 100 mph)",
                    "Vehicle make/model/color identification",
                    "Vehicle 'fingerprinting' (dents, stickers, etc.)",
                    "Real-time hotlist alerts",
                    "Integration with NCIC database",
                    "Cross-jurisdiction data sharing"
                ),
                privacyConcerns = listOf(
                    "Mass surveillance of vehicle movements",
                    "30-day data retention (may vary by jurisdiction)",
                    "Shared across law enforcement network",
                    "No warrant required for access",
                    "Can be integrated with Palantir"
                )
            )
            DeviceType.RAVEN_GUNSHOT_DETECTOR -> DeviceTypeInfo(
                name = "Raven Acoustic Gunshot Detector",
                shortDescription = "Audio Surveillance Device",
                fullDescription = "Raven devices (by Flock Safety, similar to ShotSpotter) continuously " +
                    "record audio in 5-second clips, using AI to detect gunfire. As of October 2025, " +
                    "Flock announced Ravens will also listen for 'human distress' (screaming). " +
                    "Solar-powered with cellular connectivity.",
                capabilities = listOf(
                    "Continuous audio monitoring",
                    "Gunshot detection and location",
                    "Human distress/scream detection (new)",
                    "GPS location tracking",
                    "Instant alerts to law enforcement",
                    "AI-powered audio analysis"
                ),
                privacyConcerns = listOf(
                    "Constant audio surveillance",
                    "'Human distress' detection is vague",
                    "Audio recordings may capture conversations",
                    "No consent from recorded individuals",
                    "Potential for false positives"
                )
            )
            DeviceType.PENGUIN_SURVEILLANCE -> DeviceTypeInfo(
                name = "Penguin Surveillance Device",
                shortDescription = "Mobile ALPR System",
                fullDescription = "Penguin devices are mobile surveillance systems often mounted on vehicles.",
                capabilities = listOf("Mobile license plate reading", "Vehicle tracking"),
                privacyConcerns = listOf("Mobile mass surveillance", "Covert operation")
            )
            DeviceType.PIGVISION_SYSTEM -> DeviceTypeInfo(
                name = "Pigvision System",
                shortDescription = "Surveillance Camera System",
                fullDescription = "Pigvision surveillance camera network.",
                capabilities = listOf("Video surveillance", "License plate capture"),
                privacyConcerns = listOf("Mass surveillance", "Data retention unknown")
            )
            DeviceType.MOTOROLA_POLICE_TECH -> DeviceTypeInfo(
                name = "Motorola Police Technology",
                shortDescription = "Law Enforcement Equipment",
                fullDescription = "Motorola Solutions provides extensive police technology including " +
                    "body cameras (V300/V500), in-car video systems, APX radios, and the Vigilant ALPR platform. " +
                    "Evidence is typically stored in their CommandCentral platform.",
                capabilities = listOf(
                    "Body-worn camera recording",
                    "In-car video systems",
                    "Two-way radio communications",
                    "ALPR (Vigilant platform)",
                    "Real-time video streaming",
                    "GPS location tracking"
                ),
                privacyConcerns = listOf(
                    "Continuous recording capability",
                    "Cloud evidence storage",
                    "Cross-agency data sharing",
                    "Facial recognition integration potential"
                )
            )
            DeviceType.AXON_POLICE_TECH -> DeviceTypeInfo(
                name = "Axon Police Technology",
                shortDescription = "Body Cameras & TASERs",
                fullDescription = "Axon (formerly TASER International) is the dominant body camera provider " +
                    "for US law enforcement. They also make TASERs, in-car cameras, and the Evidence.com " +
                    "cloud storage platform. Axon has been expanding into AI-powered features.",
                capabilities = listOf(
                    "Body camera recording (Body 2/3/4)",
                    "TASER deployment logging",
                    "Automatic recording triggers",
                    "Evidence.com cloud storage",
                    "Real-time streaming (Axon Respond)",
                    "AI-powered redaction and transcription"
                ),
                privacyConcerns = listOf(
                    "Massive video evidence database",
                    "AI/facial recognition features",
                    "Third-party cloud storage",
                    "Potential for covert recording",
                    "Data retention policies vary by agency"
                )
            )
            DeviceType.L3HARRIS_SURVEILLANCE -> DeviceTypeInfo(
                name = "L3Harris Surveillance",
                shortDescription = "Advanced Surveillance Systems",
                fullDescription = "L3Harris Technologies manufactures advanced surveillance equipment " +
                    "including cell site simulators (StingRay/Hailstorm), radio systems, and ISR " +
                    "(Intelligence, Surveillance, and Reconnaissance) equipment.",
                capabilities = listOf(
                    "Radio communications systems",
                    "Electronic surveillance",
                    "SIGINT capabilities",
                    "Tactical communications"
                ),
                privacyConcerns = listOf(
                    "Military-grade surveillance tech",
                    "Cell site simulator manufacturer",
                    "Little public accountability"
                )
            )
            DeviceType.CELLEBRITE_FORENSICS -> DeviceTypeInfo(
                name = "Mobile Forensics Device",
                shortDescription = "Phone Data Extraction",
                fullDescription = "Cellebrite UFED and similar devices can extract data from locked " +
                    "mobile phones, including deleted messages, call logs, photos, and app data. " +
                    "Used by law enforcement to access suspects' phones, often without warrants.",
                capabilities = listOf(
                    "Bypass phone lock screens",
                    "Extract deleted data",
                    "Access encrypted apps",
                    "Clone entire phone contents",
                    "Crack passwords/PINs",
                    "Extract cloud account data"
                ),
                privacyConcerns = listOf(
                    "Complete phone data extraction",
                    "Often used without warrants",
                    "Can access encrypted messaging apps",
                    "Recovers deleted content",
                    "Used at traffic stops in some jurisdictions"
                )
            )
            DeviceType.BODY_CAMERA -> DeviceTypeInfo(
                name = "Body-Worn Camera",
                shortDescription = "Police Body Camera",
                fullDescription = "Body-worn cameras record officer interactions with the public. " +
                    "While intended for accountability, they also create extensive surveillance footage " +
                    "of everyone officers encounter.",
                capabilities = listOf(
                    "Video and audio recording",
                    "GPS location logging",
                    "Automatic activation triggers",
                    "Real-time streaming capability",
                    "Night vision/low-light recording"
                ),
                privacyConcerns = listOf(
                    "Records bystanders without consent",
                    "Footage retention varies (30 days to years)",
                    "Can be used for facial recognition",
                    "Officers can review before writing reports",
                    "Release policies often favor police"
                )
            )
            DeviceType.POLICE_RADIO -> DeviceTypeInfo(
                name = "Police Radio System",
                shortDescription = "Law Enforcement Communications",
                fullDescription = "Modern police radios use encrypted digital protocols and often " +
                    "include GPS tracking, emergency alerts, and data transmission capabilities.",
                capabilities = listOf(
                    "Encrypted voice communications",
                    "GPS location tracking",
                    "Data transmission",
                    "Emergency signaling",
                    "Inter-agency communication"
                ),
                privacyConcerns = listOf(
                    "Encryption prevents public monitoring",
                    "Location tracking of officers/suspects",
                    "Interoperability with surveillance systems"
                )
            )
            DeviceType.STINGRAY_IMSI -> DeviceTypeInfo(
                name = "Cell Site Simulator (StingRay)",
                shortDescription = "IMSI Catcher / Fake Cell Tower",
                fullDescription = "Cell site simulators (StingRay, Hailstorm, Kingfish, Crossbow, etc.) are " +
                    "portable devices that impersonate legitimate cell towers to intercept mobile communications. " +
                    "When your phone connects to one, it captures your IMSI (unique SIM identifier), IMEI (device ID), " +
                    "and can intercept calls, texts, and data. These devices affect ALL phones in range (typically 1-2 km), " +
                    "not just the target, making them a mass surveillance tool.\n\n" +
                    "🔍 THIS DETECTION was triggered by anomalous cellular behavior on your device - " +
                    "your phone may have experienced an encryption downgrade, unexpected tower switch, " +
                    "or connected to a suspicious network identifier.",
                capabilities = listOf(
                    "Capture IMSI/IMEI from all phones in range (~1-2 km radius)",
                    "Track phone locations to within a few meters",
                    "Intercept calls, SMS, and data traffic",
                    "Force 4G/5G phones to downgrade to 2G (weak/no encryption)",
                    "Perform man-in-the-middle attacks on communications",
                    "Deny cell service selectively or entirely",
                    "Identify phone make, model, and installed apps",
                    "Clone phone identifiers for impersonation"
                ),
                privacyConcerns = listOf(
                    "Mass surveillance - captures data from EVERYONE nearby, not just targets",
                    "Used under NDA - police often hide usage from courts and defense attorneys",
                    "Can intercept encrypted app traffic via downgrade attacks",
                    "No warrant required in many jurisdictions (pen register theory)",
                    "Disrupts legitimate cell service for entire area",
                    "Data retention policies are opaque or nonexistent",
                    "Often deployed at protests, political events, and public gatherings",
                    "FBI requires local police to drop cases rather than reveal usage"
                ),
                recommendations = listOf(
                    "🛡️ IMMEDIATE: Enable airplane mode, then re-enable only WiFi if needed",
                    "📱 Use Signal, WhatsApp, or other E2E encrypted apps for sensitive communications",
                    "📍 Note your location and time - document for potential legal challenges",
                    "🚶 Leave the area if possible - StingRays have limited range (~1-2 km)",
                    "⚙️ Disable 2G on your phone if supported (Settings → Network → Preferred type → LTE/5G only)",
                    "🔒 Avoid making regular phone calls or SMS - use encrypted messaging instead",
                    "📸 Look for suspicious vehicles (vans, SUVs) with antennas or running generators",
                    "⚠️ FALSE POSITIVE? This could also be triggered by poor coverage, moving between towers, or network maintenance"
                )
            )
            DeviceType.ROGUE_AP -> DeviceTypeInfo(
                name = "Rogue Access Point",
                shortDescription = "Unauthorized WiFi Access Point",
                fullDescription = "A rogue access point is an unauthorized wireless device that may be " +
                    "attempting to intercept network traffic. This could be an 'evil twin' attack, " +
                    "karma attack, or surveillance equipment designed to capture communications.",
                capabilities = listOf(
                    "Intercept wireless traffic",
                    "Capture login credentials",
                    "Man-in-the-middle attacks",
                    "Session hijacking",
                    "SSL stripping"
                ),
                privacyConcerns = listOf(
                    "All network traffic may be captured",
                    "Credentials and sensitive data at risk",
                    "Location tracking through WiFi",
                    "Device fingerprinting"
                ),
                recommendations = listOf(
                    "🛡️ Disconnect from this network immediately",
                    "🔒 Use a VPN for all internet activity",
                    "📱 Verify network authenticity before connecting",
                    "⚠️ Avoid entering sensitive information"
                )
            )
            DeviceType.HIDDEN_CAMERA -> DeviceTypeInfo(
                name = "Hidden Camera",
                shortDescription = "Covert Video Surveillance",
                fullDescription = "A WiFi-enabled hidden camera detected through network patterns. " +
                    "These devices may be disguised as everyday objects and stream video over WiFi.",
                capabilities = listOf(
                    "Video recording",
                    "Live streaming",
                    "Motion detection",
                    "Night vision recording",
                    "Cloud storage upload"
                ),
                privacyConcerns = listOf(
                    "Invasion of privacy in private spaces",
                    "Recording without consent",
                    "Footage may be stored indefinitely",
                    "May be accessed remotely"
                ),
                recommendations = listOf(
                    "🔍 Search for the physical device",
                    "📍 Note the signal strength to locate it",
                    "🚨 Report to authorities if in rental/hotel",
                    "📸 Document evidence before removal"
                )
            )
            DeviceType.SURVEILLANCE_VAN -> DeviceTypeInfo(
                name = "Surveillance Van",
                shortDescription = "Mobile Surveillance Platform",
                fullDescription = "A mobile hotspot matching patterns associated with surveillance vehicles. " +
                    "These may be law enforcement, private investigators, or other monitoring operations.",
                capabilities = listOf(
                    "Mobile surveillance platform",
                    "Multiple monitoring systems",
                    "Extended area coverage",
                    "Cellular interception capability"
                ),
                privacyConcerns = listOf(
                    "Targeted surveillance operations",
                    "May include multiple monitoring technologies",
                    "Can follow subjects over distance",
                    "Often unmarked and inconspicuous"
                ),
                recommendations = listOf(
                    "🚶 Leave the area if possible",
                    "📍 Note vehicle descriptions",
                    "📱 Use encrypted communications",
                    "🔒 Enable airplane mode if concerned"
                )
            )
            DeviceType.TRACKING_DEVICE -> DeviceTypeInfo(
                name = "Tracking Device",
                shortDescription = "Location Tracking Device",
                fullDescription = "A device designed to track location, which may be placed on vehicles " +
                    "or personal belongings. Can transmit location data via WiFi or cellular.",
                capabilities = listOf(
                    "GPS location tracking",
                    "Movement history logging",
                    "Real-time location updates",
                    "Geofence alerts"
                ),
                privacyConcerns = listOf(
                    "Continuous location monitoring",
                    "Movement pattern analysis",
                    "May be placed without consent",
                    "Data shared with third parties"
                ),
                recommendations = listOf(
                    "🔍 Search vehicle and belongings",
                    "📍 Locate using signal strength",
                    "🚨 Report unauthorized tracking to police",
                    "📸 Document before removal"
                )
            )
            DeviceType.RF_JAMMER -> DeviceTypeInfo(
                name = "RF Jammer",
                shortDescription = "Radio Frequency Jammer",
                fullDescription = "A device that disrupts wireless communications by emitting interference. " +
                    "RF jammers are illegal in most jurisdictions but may be used by law enforcement or criminals.",
                capabilities = listOf(
                    "Block cellular signals",
                    "Disrupt WiFi communications",
                    "Prevent GPS tracking",
                    "Interfere with Bluetooth"
                ),
                privacyConcerns = listOf(
                    "Prevents emergency calls",
                    "Blocks legitimate communications",
                    "May indicate criminal activity nearby",
                    "Illegal to operate in most places"
                ),
                recommendations = listOf(
                    "🚨 Leave area - may indicate robbery or attack",
                    "📍 Note location and time",
                    "📞 Report to authorities (from outside range)",
                    "⚠️ Do not attempt to locate the device"
                )
            )
            DeviceType.DRONE -> DeviceTypeInfo(
                name = "Drone/UAV",
                shortDescription = "Unmanned Aerial Vehicle",
                fullDescription = "A drone detected via its WiFi control signal. Drones can carry cameras " +
                    "and other sensors for aerial surveillance.",
                capabilities = listOf(
                    "Aerial video/photo capture",
                    "Thermal imaging",
                    "GPS tracking",
                    "Extended range surveillance",
                    "Face recognition capability"
                ),
                privacyConcerns = listOf(
                    "Surveillance from above",
                    "Difficult to detect visually",
                    "Can follow subjects",
                    "May record private property"
                ),
                recommendations = listOf(
                    "🔍 Look up to locate the drone",
                    "📱 Use the signal strength to find operator",
                    "🚨 Report if over private property",
                    "🏠 Move indoors if concerned"
                )
            )
            DeviceType.SURVEILLANCE_INFRASTRUCTURE -> DeviceTypeInfo(
                name = "Surveillance Infrastructure",
                shortDescription = "Fixed Surveillance System",
                fullDescription = "A concentration of surveillance devices indicating organized monitoring " +
                    "infrastructure such as camera networks or sensor arrays.",
                capabilities = listOf(
                    "Multiple camera coverage",
                    "License plate recognition",
                    "Face recognition",
                    "Behavior analysis",
                    "Cross-camera tracking"
                ),
                privacyConcerns = listOf(
                    "Comprehensive area monitoring",
                    "Long-term data retention",
                    "Automated tracking capabilities",
                    "Integration with law enforcement databases"
                )
            )
            DeviceType.ULTRASONIC_BEACON -> DeviceTypeInfo(
                name = "Ultrasonic Beacon",
                shortDescription = "Ultrasonic Tracking Device",
                fullDescription = "An ultrasonic beacon emitting inaudible sounds for cross-device tracking. " +
                    "These are used by advertisers to link your devices and track you across locations.",
                capabilities = listOf(
                    "Cross-device tracking",
                    "Location tracking in stores",
                    "Ad targeting coordination",
                    "Linking anonymous browsing to identity"
                ),
                privacyConcerns = listOf(
                    "Tracks without visual indication",
                    "Links all your devices together",
                    "Retail location tracking",
                    "Advertising profile building",
                    "Works even with WiFi/Bluetooth off"
                ),
                recommendations = listOf(
                    "🔇 These signals are inaudible to humans",
                    "📱 Some apps can block ultrasonic tracking",
                    "🏪 Common in retail stores and TV ads",
                    "🔒 Consider ultrasonic firewall apps"
                )
            )
            DeviceType.POLICE_VEHICLE -> DeviceTypeInfo(
                name = "Police/Emergency Vehicle",
                shortDescription = "Emergency Vehicle Detected",
                fullDescription = "A police car, ambulance, or fire truck has been detected nearby via its " +
                    "emergency lighting system's Bluetooth connection. Modern emergency vehicles use BLE " +
                    "to synchronize their lightbars (Whelen CenCom, Federal Signal, etc.) and may also " +
                    "have body camera triggers (Axon Signal) that activate when sirens are turned on.",
                capabilities = listOf(
                    "Emergency lightbar synchronization",
                    "Automatic body camera activation",
                    "Siren/PA system integration",
                    "In-car video triggering",
                    "GPS/AVL location reporting"
                ),
                privacyConcerns = listOf(
                    "Officers may be actively recording",
                    "Vehicle likely has dash cameras",
                    "ALPR systems often installed",
                    "Location data transmitted to dispatch"
                ),
                recommendations = listOf(
                    "🚔 Police or emergency vehicle is nearby",
                    "📹 Assume you are being recorded",
                    "📍 Note time and location if relevant",
                    "🚗 May be stationary or moving through area"
                )
            )
            DeviceType.FLEET_VEHICLE -> DeviceTypeInfo(
                name = "Fleet Vehicle",
                shortDescription = "Commercial/Government Fleet Vehicle",
                fullDescription = "A vehicle with fleet management hardware has been detected. This could be " +
                    "a police vehicle, government car, utility truck, or commercial fleet vehicle. These " +
                    "vehicles often have WiFi hotspots using Sierra Wireless or Cradlepoint routers, which " +
                    "are commonly used by law enforcement for mobile data terminals and surveillance equipment.",
                capabilities = listOf(
                    "Mobile WiFi hotspot for in-vehicle systems",
                    "GPS tracking and route logging",
                    "Mobile data terminal connectivity",
                    "Potential ALPR/camera data uplink",
                    "Fleet management and dispatch integration"
                ),
                privacyConcerns = listOf(
                    "May be an unmarked police vehicle",
                    "Could have surveillance equipment",
                    "Vehicle movements are tracked",
                    "Hidden SSID networks are common"
                ),
                recommendations = listOf(
                    "🚐 Fleet vehicle detected via mobile router",
                    "👀 Could be police, utility, or commercial",
                    "📶 Strong signal = vehicle is close",
                    "🔍 Hidden SSIDs are suspicious"
                )
            )
            DeviceType.SATELLITE_NTN -> DeviceTypeInfo(
                name = "Satellite NTN Device",
                shortDescription = "Non-Terrestrial Network Connection",
                fullDescription = "Connection detected via satellite-based Non-Terrestrial Network (NTN). " +
                    "This could be legitimate D2D service (T-Mobile Starlink, Skylo) or potentially " +
                    "suspicious if unexpected in an area with good terrestrial coverage.",
                capabilities = listOf(
                    "SMS/MMS messaging (provider dependent)",
                    "Emergency SOS services",
                    "Location sharing",
                    "Limited data for select apps"
                ),
                privacyConcerns = listOf(
                    "Satellite connections can be spoofed by ground stations",
                    "NTN parameters can reveal user location",
                    "Unknown satellites may intercept communications"
                ),
                recommendations = listOf(
                    "Verify expected satellite provider",
                    "Check if terrestrial coverage is available",
                    "Monitor for timing anomalies"
                )
            )
            DeviceType.RF_INTERFERENCE -> DeviceTypeInfo(
                name = "RF Interference",
                shortDescription = "Radio Frequency Interference Detected",
                fullDescription = "Significant change in the RF environment detected. This could indicate " +
                    "natural interference, new equipment nearby, or intentional signal manipulation.",
                capabilities = listOf(
                    "Disrupts wireless communications",
                    "May affect WiFi, Bluetooth, cellular",
                    "Can mask other surveillance activity"
                ),
                privacyConcerns = listOf(
                    "May be used to disrupt security systems",
                    "Could be part of surveillance operation",
                    "May precede other attacks"
                )
            )
            DeviceType.RF_ANOMALY -> DeviceTypeInfo(
                name = "RF Environment Anomaly",
                shortDescription = "Unusual RF Activity Pattern",
                fullDescription = "Unusual patterns detected in the local RF environment. This is a " +
                    "low-confidence detection that may indicate surveillance equipment or environmental changes.",
                capabilities = listOf("Varies depending on source"),
                privacyConcerns = listOf("May indicate covert RF equipment nearby")
            )
            DeviceType.HIDDEN_TRANSMITTER -> DeviceTypeInfo(
                name = "Hidden Transmitter",
                shortDescription = "Possible Covert RF Transmission",
                fullDescription = "A potential hidden RF transmitter has been detected. This could be " +
                    "a bug, tracker, or other covert transmission device.",
                capabilities = listOf(
                    "Continuous RF transmission",
                    "May transmit audio/video/data",
                    "Could be a tracking device"
                ),
                privacyConcerns = listOf(
                    "Covert audio/video surveillance",
                    "Location tracking",
                    "Data exfiltration"
                )
            )
            DeviceType.GNSS_SPOOFER -> DeviceTypeInfo(
                name = "GNSS Spoofer",
                shortDescription = "GPS/GNSS Signal Spoofing Detected",
                fullDescription = "Fake GPS/GNSS signals detected. Someone may be attempting to " +
                    "manipulate your location data or deceive GPS-dependent systems.",
                capabilities = listOf(
                    "Broadcast fake GPS coordinates",
                    "Manipulate location-based services",
                    "Deceive navigation systems"
                ),
                privacyConcerns = listOf(
                    "Location data manipulation",
                    "May be used for tracking or misdirection",
                    "Can affect geofencing and location logs"
                )
            )
            DeviceType.GNSS_JAMMER -> DeviceTypeInfo(
                name = "GNSS Jammer",
                shortDescription = "GPS/GNSS Signal Jamming Detected",
                fullDescription = "GPS/GNSS signals are being blocked or degraded. This prevents " +
                    "accurate location determination and may indicate a surveillance operation.",
                capabilities = listOf(
                    "Block GPS/GNSS signals",
                    "Prevent location tracking",
                    "Disable GPS-dependent security"
                ),
                privacyConcerns = listOf(
                    "May be used to avoid location tracking",
                    "Can indicate criminal activity nearby",
                    "Affects emergency services"
                )
            )
            DeviceType.UNKNOWN_SURVEILLANCE -> DeviceTypeInfo(
                name = "Unknown Surveillance Device",
                shortDescription = "Unidentified Surveillance Equipment",
                fullDescription = "This device matches patterns associated with surveillance equipment " +
                    "but the specific manufacturer/model is unknown.",
                capabilities = listOf("Unknown - potentially ALPR or audio surveillance"),
                privacyConcerns = listOf("Unknown data collection practices")
            )
            // Smart Home / IoT Surveillance Devices
            DeviceType.RING_DOORBELL -> DeviceTypeInfo(
                name = "Ring Doorbell/Camera",
                shortDescription = "Amazon Smart Doorbell",
                fullDescription = "Ring doorbells and cameras are connected to Amazon's Neighbors network. " +
                    "Ring has partnerships with over 2,500 police departments allowing law enforcement to " +
                    "request video footage, sometimes without a warrant.",
                capabilities = listOf(
                    "Video recording (1080p-4K)",
                    "Two-way audio",
                    "Motion detection",
                    "Police footage sharing via Neighbors"
                ),
                privacyConcerns = listOf(
                    "Footage shared with 2,500+ police agencies",
                    "Neighbors app creates surveillance network",
                    "Cloud storage on Amazon servers"
                )
            )
            DeviceType.NEST_CAMERA -> DeviceTypeInfo(
                name = "Nest/Google Camera",
                shortDescription = "Google Smart Camera",
                fullDescription = "Nest cameras are connected to Google's cloud infrastructure.",
                capabilities = listOf("HD/4K video", "AI-powered detection", "Google Home integration"),
                privacyConcerns = listOf("Data processed by Google AI", "Cloud storage", "May be subpoenaed")
            )
            DeviceType.AMAZON_SIDEWALK -> DeviceTypeInfo(
                name = "Amazon Sidewalk Device",
                shortDescription = "Amazon Mesh Network",
                fullDescription = "Amazon Sidewalk creates a shared mesh network using Echo and Ring devices.",
                capabilities = listOf("Mesh network connectivity", "Extends smart home range"),
                privacyConcerns = listOf("Shares your bandwidth", "Creates neighborhood tracking network")
            )
            DeviceType.WYZE_CAMERA -> DeviceTypeInfo(
                name = "Wyze Camera",
                shortDescription = "Budget Smart Camera",
                fullDescription = "Wyze cameras are budget-friendly smart home cameras with cloud connectivity.",
                capabilities = listOf("HD video", "Motion detection", "Cloud storage"),
                privacyConcerns = listOf("Cloud-dependent", "Data breach history")
            )
            DeviceType.ARLO_CAMERA -> DeviceTypeInfo(
                name = "Arlo Camera",
                shortDescription = "Wireless Security Camera",
                fullDescription = "Arlo wireless security cameras with cloud storage and AI features.",
                capabilities = listOf("4K video", "Wire-free operation", "AI detection"),
                privacyConcerns = listOf("Cloud storage required", "Subscription model")
            )
            DeviceType.EUFY_CAMERA -> DeviceTypeInfo(
                name = "Eufy Camera",
                shortDescription = "Anker Security Camera",
                fullDescription = "Eufy security cameras advertise local storage but have been found to send data to cloud.",
                capabilities = listOf("Local storage option", "AI detection"),
                privacyConcerns = listOf("Misleading local storage claims", "Unencrypted cloud uploads discovered")
            )
            DeviceType.BLINK_CAMERA -> DeviceTypeInfo(
                name = "Blink Camera",
                shortDescription = "Amazon Blink Camera",
                fullDescription = "Blink cameras are Amazon-owned, similar privacy concerns to Ring.",
                capabilities = listOf("HD video", "Battery powered", "Cloud storage"),
                privacyConcerns = listOf("Amazon ecosystem", "Cloud storage", "Police partnership potential")
            )
            DeviceType.SIMPLISAFE_DEVICE -> DeviceTypeInfo(
                name = "SimpliSafe Device",
                shortDescription = "SimpliSafe Security",
                fullDescription = "SimpliSafe home security system with professional monitoring option.",
                capabilities = listOf("Intrusion detection", "Video doorbells"),
                privacyConcerns = listOf("Professional monitoring access", "Cloud connectivity")
            )
            DeviceType.ADT_DEVICE -> DeviceTypeInfo(
                name = "ADT Security Device",
                shortDescription = "ADT Security System",
                fullDescription = "ADT is a major security provider with deep law enforcement relationships.",
                capabilities = listOf("Professional monitoring", "Video surveillance"),
                privacyConcerns = listOf("Law enforcement partnerships", "Central monitoring station")
            )
            DeviceType.VIVINT_DEVICE -> DeviceTypeInfo(
                name = "Vivint Smart Home",
                shortDescription = "Vivint Security",
                fullDescription = "Vivint smart home security with AI-powered cameras and monitoring.",
                capabilities = listOf("AI camera analytics", "Professional monitoring"),
                privacyConcerns = listOf("Professional monitoring", "AI video analysis")
            )
            // Retail/Commercial Tracking
            DeviceType.BLUETOOTH_BEACON -> DeviceTypeInfo(
                name = "Bluetooth Beacon",
                shortDescription = "Location Beacon",
                fullDescription = "Bluetooth beacons are used for indoor positioning and retail tracking.",
                capabilities = listOf("Indoor positioning", "Proximity detection"),
                privacyConcerns = listOf("Tracks movement in stores", "Links to mobile apps")
            )
            DeviceType.RETAIL_TRACKER -> DeviceTypeInfo(
                name = "Retail Tracker",
                shortDescription = "Store Tracking System",
                fullDescription = "Retail tracking systems monitor customer movement and dwell time in stores.",
                capabilities = listOf("Foot traffic analysis", "Dwell time tracking"),
                privacyConcerns = listOf("Tracks without consent", "Links to purchase data")
            )
            DeviceType.CROWD_ANALYTICS -> DeviceTypeInfo(
                name = "Crowd Analytics Sensor",
                shortDescription = "People Counting System",
                fullDescription = "Sensors that count people and analyze crowd movement patterns.",
                capabilities = listOf("People counting", "Flow analysis"),
                privacyConcerns = listOf("Mass tracking", "Behavior analysis")
            )
            DeviceType.FACIAL_RECOGNITION -> DeviceTypeInfo(
                name = "Facial Recognition System",
                shortDescription = "Face Detection Camera",
                fullDescription = "Camera system with facial recognition capabilities for identification.",
                capabilities = listOf("Face detection", "Identity matching"),
                privacyConcerns = listOf("Biometric data collection", "Identity tracking")
            )
            // Tracker Devices
            DeviceType.AIRTAG -> DeviceTypeInfo(
                name = "Apple AirTag",
                shortDescription = "Apple Tracker",
                fullDescription = "Apple AirTag Bluetooth tracker. Can be misused for stalking.",
                capabilities = listOf("Precision Finding", "Find My network"),
                privacyConcerns = listOf("Stalking potential", "Movement tracking"),
                recommendations = listOf("Check if you're being tracked unexpectedly", "iOS alerts to unknown AirTags")
            )
            DeviceType.TILE_TRACKER -> DeviceTypeInfo(
                name = "Tile Tracker",
                shortDescription = "Tile Bluetooth Tracker",
                fullDescription = "Tile Bluetooth trackers for finding lost items. Now owned by Life360.",
                capabilities = listOf("Bluetooth tracking", "Community finding"),
                privacyConcerns = listOf("Life360 data practices", "Stalking potential")
            )
            DeviceType.SAMSUNG_SMARTTAG -> DeviceTypeInfo(
                name = "Samsung SmartTag",
                shortDescription = "Samsung Tracker",
                fullDescription = "Samsung's Bluetooth tracker using Galaxy Find Network.",
                capabilities = listOf("UWB precision finding", "Galaxy network"),
                privacyConcerns = listOf("Movement tracking", "Stalking potential")
            )
            DeviceType.GENERIC_BLE_TRACKER -> DeviceTypeInfo(
                name = "BLE Tracker",
                shortDescription = "Bluetooth Tracker Device",
                fullDescription = "Generic Bluetooth tracker device detected.",
                capabilities = listOf("Bluetooth tracking"),
                privacyConcerns = listOf("Movement tracking", "Stalking potential")
            )
            // Traffic Enforcement
            DeviceType.SPEED_CAMERA -> DeviceTypeInfo(
                name = "Speed Camera",
                shortDescription = "Speed Enforcement Camera",
                fullDescription = "Automated speed enforcement camera that photographs speeding vehicles.",
                capabilities = listOf("Speed measurement", "License plate capture"),
                privacyConcerns = listOf("Vehicle tracking", "Photo evidence stored")
            )
            DeviceType.RED_LIGHT_CAMERA -> DeviceTypeInfo(
                name = "Red Light Camera",
                shortDescription = "Intersection Enforcement Camera",
                fullDescription = "Camera that captures vehicles running red lights.",
                capabilities = listOf("Intersection monitoring", "License plate capture"),
                privacyConcerns = listOf("Intersection surveillance", "Data retention")
            )
            DeviceType.TOLL_READER -> DeviceTypeInfo(
                name = "Toll/E-ZPass Reader",
                shortDescription = "Electronic Toll Collection",
                fullDescription = "Electronic toll collection system that reads transponders and plates.",
                capabilities = listOf("Transponder reading", "License plate capture"),
                privacyConcerns = listOf("Travel pattern tracking", "Government data access")
            )
            DeviceType.TRAFFIC_SENSOR -> DeviceTypeInfo(
                name = "Traffic Sensor",
                shortDescription = "Traffic Monitoring Sensor",
                fullDescription = "Sensor for monitoring traffic flow and conditions.",
                capabilities = listOf("Vehicle counting", "Speed estimation"),
                privacyConcerns = listOf("Movement pattern data")
            )
            // Law Enforcement Specific
            DeviceType.SHOTSPOTTER -> DeviceTypeInfo(
                name = "ShotSpotter Sensor",
                shortDescription = "Gunshot Detection System",
                fullDescription = "ShotSpotter acoustic sensors detect gunfire and continuously monitor audio.",
                capabilities = listOf("Gunshot detection", "Audio triangulation"),
                privacyConcerns = listOf("Continuous audio surveillance", "May capture conversations", "False positive issues")
            )
            DeviceType.CLEARVIEW_AI -> DeviceTypeInfo(
                name = "Clearview AI System",
                shortDescription = "Facial Recognition Database",
                fullDescription = "Clearview AI scraped billions of photos to create a massive facial recognition database.",
                capabilities = listOf("Face matching from any photo", "Social media linking"),
                privacyConcerns = listOf("Scraped photos without consent", "Used by thousands of agencies", "No opt-out")
            )
            DeviceType.PALANTIR_DEVICE -> DeviceTypeInfo(
                name = "Palantir Device",
                shortDescription = "Data Analytics Platform",
                fullDescription = "Palantir provides data integration and analytics to law enforcement.",
                capabilities = listOf("Cross-database linking", "Pattern analysis"),
                privacyConcerns = listOf("Aggregates multiple data sources", "Predictive policing")
            )
            DeviceType.GRAYKEY_DEVICE -> DeviceTypeInfo(
                name = "GrayKey Forensics",
                shortDescription = "Phone Cracking Device",
                fullDescription = "GrayKey devices can unlock iPhones and Android phones for data extraction.",
                capabilities = listOf("iPhone unlocking", "Full data extraction"),
                privacyConcerns = listOf("Breaks phone security", "Full data access")
            )
            // Network Surveillance
            DeviceType.WIFI_PINEAPPLE -> DeviceTypeInfo(
                name = "WiFi Pineapple",
                shortDescription = "Network Auditing/Attack Tool",
                fullDescription = "Hak5 WiFi Pineapple is used for network security testing but can be misused.",
                capabilities = listOf("Evil twin attacks", "Credential capture"),
                privacyConcerns = listOf("MITM attacks", "Password theft"),
                recommendations = listOf("Don't connect to unknown WiFi", "Use VPN")
            )
            DeviceType.PACKET_SNIFFER -> DeviceTypeInfo(
                name = "Packet Sniffer",
                shortDescription = "Network Traffic Analyzer",
                fullDescription = "Device capturing network traffic for analysis.",
                capabilities = listOf("Traffic capture", "Protocol analysis"),
                privacyConcerns = listOf("Captures unencrypted data", "Password interception")
            )
            DeviceType.MAN_IN_MIDDLE -> DeviceTypeInfo(
                name = "MITM Device",
                shortDescription = "Man-in-the-Middle Attack Device",
                fullDescription = "Device positioned between user and network to intercept communications.",
                capabilities = listOf("Traffic interception", "SSL stripping"),
                privacyConcerns = listOf("All traffic exposed", "Identity theft risk")
            )
            // Misc Surveillance
            DeviceType.LICENSE_PLATE_READER -> DeviceTypeInfo(
                name = "License Plate Reader",
                shortDescription = "ALPR Camera",
                fullDescription = "Automated License Plate Reader camera system.",
                capabilities = listOf("Plate capture", "Vehicle identification"),
                privacyConcerns = listOf("Mass vehicle tracking", "Location history")
            )
            DeviceType.CCTV_CAMERA -> DeviceTypeInfo(
                name = "CCTV Camera",
                shortDescription = "Closed-Circuit Camera",
                fullDescription = "Standard surveillance camera system.",
                capabilities = listOf("Video recording", "Motion detection"),
                privacyConcerns = listOf("Continuous monitoring", "Recording retention")
            )
            DeviceType.PTZ_CAMERA -> DeviceTypeInfo(
                name = "PTZ Camera",
                shortDescription = "Pan-Tilt-Zoom Camera",
                fullDescription = "Camera with remote pan, tilt, and zoom capabilities for active surveillance.",
                capabilities = listOf("Remote directional control", "Optical zoom"),
                privacyConcerns = listOf("Active tracking capability")
            )
            DeviceType.THERMAL_CAMERA -> DeviceTypeInfo(
                name = "Thermal Camera",
                shortDescription = "Infrared/Thermal Imaging",
                fullDescription = "Camera using thermal imaging to detect heat signatures.",
                capabilities = listOf("Heat detection", "Night operation"),
                privacyConcerns = listOf("Sees through concealment", "Activity detection in private spaces")
            )
            DeviceType.NIGHT_VISION -> DeviceTypeInfo(
                name = "Night Vision Device",
                shortDescription = "Low-Light Imaging",
                fullDescription = "Device using infrared or image intensification for night surveillance.",
                capabilities = listOf("Low-light operation"),
                privacyConcerns = listOf("Surveillance in darkness")
            )
        }
    }
    
    data class DeviceTypeInfo(
        val name: String,
        val shortDescription: String,
        val fullDescription: String,
        val capabilities: List<String>,
        val privacyConcerns: List<String>,
        val recommendations: List<String> = emptyList()
    )
    
    // ==================== OUI Manufacturer Lookup ====================
    // Common manufacturer OUIs for quick identification
    private val ouiManufacturers = mapOf(
        "00:00:0C" to "Cisco",
        "00:01:42" to "Cisco",
        "00:0C:29" to "VMware",
        "00:0D:3A" to "Microsoft",
        "00:14:22" to "Dell",
        "00:17:88" to "Philips",
        "00:1A:11" to "Google",
        "00:1E:C2" to "Apple",
        "00:23:32" to "Apple",
        "00:25:00" to "Apple",
        "00:26:BB" to "Apple",
        "00:50:56" to "VMware",
        "08:00:27" to "Oracle VirtualBox",
        "14:13:46" to "Xiaomi",
        "18:65:90" to "Apple",
        "28:6A:B8" to "Apple",
        "2C:BE:08" to "Apple",
        "34:23:BA" to "Xiaomi",
        "38:F9:D3" to "Apple",
        "3C:06:30" to "Apple",
        "40:4E:36" to "HP",
        "44:D9:E7" to "Ubiquiti",
        "50:29:4D" to "Quectel",
        "54:60:09" to "Google",
        "58:CB:52" to "Google",
        "5C:CF:7F" to "Espressif",
        "60:01:94" to "Espressif",
        "70:B3:D5" to "IEEE Registration",
        "78:4F:43" to "Apple",
        "80:6D:97" to "Samsung",
        "84:D8:1B" to "Apple",
        "88:E9:FE" to "Apple",
        "8C:85:90" to "Apple",
        "94:65:2D" to "OnePlus",
        "98:D6:F7" to "LG",
        "9C:8E:99" to "Hewlett Packard",
        "A4:77:33" to "Google",
        "A4:C6:39" to "Intel",
        "AC:37:43" to "HTC",
        "B0:34:95" to "Apple",
        "B4:F1:DA" to "LG",
        "B8:27:EB" to "Raspberry Pi",
        "BC:83:85" to "Microsoft",
        "C0:EE:FB" to "OnePlus",
        "C8:3D:D4" to "CyberTAN",
        "CC:46:D6" to "Cisco",
        "D0:03:4B" to "Apple",
        "D4:61:9D" to "Apple",
        "D4:CA:6E" to "u-blox",
        "D8:C7:71" to "Telit",
        "DC:A6:32" to "Raspberry Pi",
        "E0:5F:45" to "Apple",
        "E4:5F:01" to "Raspberry Pi",
        "EC:85:2F" to "Apple",
        "F0:18:98" to "Apple",
        "F4:F5:D8" to "Google",
        "F8:1A:67" to "TP-Link",
        "FC:A1:3E" to "Samsung"
    )
    
    /**
     * Try to identify manufacturer from MAC OUI (first 3 octets)
     */
    fun getManufacturerFromOui(oui: String): String? {
        val normalizedOui = oui.uppercase().replace("-", ":").take(8)
        return ouiManufacturers[normalizedOui] ?: macPrefixes.find {
            normalizedOui.startsWith(it.prefix.uppercase())
        }?.manufacturer
    }

    /**
     * Get all built-in patterns as SurveillancePattern objects.
     * Used for merging with custom/downloaded patterns.
     */
    val allPatterns: List<SurveillancePattern> by lazy {
        val patterns = mutableListOf<SurveillancePattern>()

        // Group SSID patterns by device type
        val ssidByDevice = ssidPatterns.groupBy { it.deviceType }
        ssidByDevice.forEach { (deviceType, detectionPatterns) ->
            patterns.add(
                SurveillancePattern(
                    id = "builtin_ssid_${deviceType.name.lowercase()}",
                    name = deviceType.displayName,
                    description = detectionPatterns.first().description,
                    deviceType = deviceType,
                    manufacturer = detectionPatterns.first().manufacturer,
                    threatLevel = scoreToThreatLevel(detectionPatterns.maxOf { it.threatScore }),
                    ssidPatterns = detectionPatterns.map { it.pattern },
                    isBuiltIn = true
                )
            )
        }

        // Group BLE name patterns by device type
        val bleByDevice = bleNamePatterns.groupBy { it.deviceType }
        bleByDevice.forEach { (deviceType, detectionPatterns) ->
            // Check if we already have an entry for this device
            val existingIndex = patterns.indexOfFirst { it.deviceType == deviceType }
            if (existingIndex >= 0) {
                // Merge BLE patterns into existing entry
                val existing = patterns[existingIndex]
                patterns[existingIndex] = existing.copy(
                    bleNamePatterns = detectionPatterns.map { it.pattern }
                )
            } else {
                patterns.add(
                    SurveillancePattern(
                        id = "builtin_ble_${deviceType.name.lowercase()}",
                        name = deviceType.displayName,
                        description = detectionPatterns.first().description,
                        deviceType = deviceType,
                        manufacturer = detectionPatterns.first().manufacturer,
                        threatLevel = scoreToThreatLevel(detectionPatterns.maxOf { it.threatScore }),
                        bleNamePatterns = detectionPatterns.map { it.pattern },
                        isBuiltIn = true
                    )
                )
            }
        }

        // Group MAC prefixes by device type
        val macByDevice = macPrefixes.groupBy { it.deviceType }
        macByDevice.forEach { (deviceType, macPrefixList) ->
            val existingIndex = patterns.indexOfFirst { it.deviceType == deviceType }
            if (existingIndex >= 0) {
                val existing = patterns[existingIndex]
                patterns[existingIndex] = existing.copy(
                    macPrefixes = macPrefixList.map { it.prefix }
                )
            } else {
                patterns.add(
                    SurveillancePattern(
                        id = "builtin_mac_${deviceType.name.lowercase()}",
                        name = deviceType.displayName,
                        description = macPrefixList.first().description,
                        deviceType = deviceType,
                        manufacturer = macPrefixList.first().manufacturer,
                        threatLevel = scoreToThreatLevel(macPrefixList.maxOf { it.threatScore }),
                        macPrefixes = macPrefixList.map { it.prefix },
                        isBuiltIn = true
                    )
                )
            }
        }

        patterns.toList()
    }
}
