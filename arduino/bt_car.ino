// ================================================================
//  BT Tank Arabası - FINAL v3
//  Uygulama protokolü: F90R30 formatı
//    [F/B][00-99] = İleri/Geri hız
//    [L/R][00-99] = Sol/Sağ dönüş şiddeti
//
//  BAĞLANTILAR:
//    BT TX→Pin 0, BT RX→Pin 1  (!! yüklerken BT'yi çıkar !!)
//    ENA→Pin 5 (Sol motor hız PWM)
//    ENB→Pin 3 (Sağ motor hız PWM)
//    IN1=6, IN2=10 (Sol yön)
//    IN3=4, IN4=9  (Sağ yön)
//    Trig=11, Echo=12, Buzzer=13
// ================================================================

const int ENA = 5;
const int IN1 = 6;
const int IN2 = 10;
const int ENB = 3;
const int IN3 = 4;
const int IN4 = 9;
const int TRIG   = 11;
const int ECHO   = 12;
const int BUZZER = 13;

// ---- Hız ayarı ----
const int MIN_SPD  = 80;   // Motorun döndüğü minimum PWM
const int MAX_SPD  = 255;

// ---- Yön düzeltme: motor ters dönüyorsa true yap ----
const bool INV_FWD  = true;   // İleri/geri ters → true
const bool INV_TURN = true;   // Sol/sağ ters    → true

// ---- Mesafe ----
const int STOP_DIST = 3;
const int WARN_DIST = 10;

// ---- Durum ----
bool hornOn   = false;
bool blockFwd = false;
long curDist  = 999;

// Paket tamponu
char pkt[6];
int  pktLen = 0;

// Mevcut motor hızları (engel kontrolü için)
int curLeftSpd  = 0;
int curRightSpd = 0;

unsigned long prevSensor = 0;
unsigned long prevBuzz   = 0;
bool          buzzState  = false;

// ================================================================
void setup() {
  pinMode(ENA, OUTPUT); pinMode(IN1, OUTPUT); pinMode(IN2, OUTPUT);
  pinMode(ENB, OUTPUT); pinMode(IN3, OUTPUT); pinMode(IN4, OUTPUT);
  pinMode(TRIG, OUTPUT); pinMode(ECHO, INPUT);
  pinMode(BUZZER, OUTPUT);
  stopAll();
  Serial.begin(9600);
}

// ================================================================
void loop() {
  unsigned long now = millis();

  // Mesafe ölçümü
  if (now - prevSensor >= 80) {
    prevSensor = now;
    curDist    = measureDist();
    blockFwd   = (curDist <= STOP_DIST);
    // Engel anında ileri gidiyorsa durdur
    if (blockFwd && (curLeftSpd > 0 || curRightSpd > 0)) stopAll();
  }

  // Bluetooth
  while (Serial.available()) {
    char c = (char)Serial.read();
    // Tek karakterli komutlar (korna)
    if (c == 'V' || c == 'W') { hornOn = true;  pktLen = 0; continue; }
    if (c == 'v' || c == 'w') { hornOn = false; pktLen = 0; continue; }
    // Paket başlangıcı F veya B
    if (c == 'F' || c == 'B') { pkt[0] = c; pktLen = 1; continue; }
    // Paket birikimi
    if (pktLen > 0 && pktLen < 6) {
      pkt[pktLen++] = c;
      if (pktLen == 6) { processPacket(); pktLen = 0; }
    }
  }

  handleBuzzer(now);
}

// ================================================================
//  Paket işleme: pkt = [F/B][D1][D0][L/R][T1][T0]
//  Örnek: F90R30 → ileri %90, sağ %30
// ================================================================
void processPacket() {
  // İleri/Geri bileşeni
  int fwdVal  = (pkt[1] - '0') * 10 + (pkt[2] - '0'); // 0-99
  int fwdSign = (pkt[0] == 'F') ? 1 : -1;
  if (INV_FWD) fwdSign = -fwdSign;

  // Sol/Sağ bileşeni
  int turnVal  = (pkt[4] - '0') * 10 + (pkt[5] - '0'); // 0-99
  int turnSign = (pkt[3] == 'R') ? 1 : -1;
  if (INV_TURN) turnSign = -turnSign;

  // 0-99 → PWM: 0 için 0, 1-99 için MIN_SPD-MAX_SPD
  int fwdPWM  = (fwdVal  == 0) ? 0 : map(fwdVal,  1, 99, MIN_SPD, MAX_SPD);
  int turnPWM = (turnVal == 0) ? 0 : map(turnVal, 1, 60, 0, MAX_SPD / 2);

  // Diferansiyel motor hızı (eksenler değiştirildi)
  // L/R ekseni → iki motoru birden sürer (düz hareket)
  // F/B ekseni → motorlar arasında fark yaratır (dönüş)
  int leftSpd  = turnPWM * turnSign + fwdPWM * fwdSign;
  int rightSpd = turnPWM * turnSign - fwdPWM * fwdSign;

  // İleri engel kontrolü (sadece ileri yönü engelle)
  if (blockFwd) {
    if (leftSpd  > 0) leftSpd  = 0;
    if (rightSpd > 0) rightSpd = 0;
  }

  setL(leftSpd);
  setR(rightSpd);
}

// ================================================================
long measureDist() {
  digitalWrite(TRIG, LOW);  delayMicroseconds(2);
  digitalWrite(TRIG, HIGH); delayMicroseconds(10);
  digitalWrite(TRIG, LOW);
  long d = pulseIn(ECHO, HIGH, 25000);
  return (d == 0) ? 400 : d / 58L;
}

// ================================================================
void setL(int spd) {
  spd = constrain(spd, -MAX_SPD, MAX_SPD);
  curLeftSpd = spd;
  if      (spd > 0) { digitalWrite(IN1, HIGH); digitalWrite(IN2, LOW);  analogWrite(ENA, spd);  }
  else if (spd < 0) { digitalWrite(IN1, LOW);  digitalWrite(IN2, HIGH); analogWrite(ENA, -spd); }
  else              { analogWrite(ENA, 0); digitalWrite(IN1, LOW); digitalWrite(IN2, LOW); }
}

void setR(int spd) {
  spd = constrain(spd, -MAX_SPD, MAX_SPD);
  curRightSpd = spd;
  if      (spd > 0) { digitalWrite(IN3, HIGH); digitalWrite(IN4, LOW);  analogWrite(ENB, spd);  }
  else if (spd < 0) { digitalWrite(IN3, LOW);  digitalWrite(IN4, HIGH); analogWrite(ENB, -spd); }
  else              { analogWrite(ENB, 0); digitalWrite(IN3, LOW); digitalWrite(IN4, LOW); }
}

void stopAll() { setL(0); setR(0); }

// ================================================================
void handleBuzzer(unsigned long now) {
  if (hornOn || curDist <= STOP_DIST) {
    digitalWrite(BUZZER, HIGH); return;
  }
  if (curDist > WARN_DIST) {
    digitalWrite(BUZZER, LOW); buzzState = false; return;
  }
  long iv = map(curDist, STOP_DIST, WARN_DIST, 80L, 800L);
  if (now - prevBuzz >= (unsigned long)iv) {
    prevBuzz  = now;
    buzzState = !buzzState;
    digitalWrite(BUZZER, buzzState ? HIGH : LOW);
  }
}
