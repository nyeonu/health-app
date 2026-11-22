package com.ny.my.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("개인정보와 건강 데이터 권한", style = MaterialTheme.typography.headlineSmall)
                        Text("앱은 Health Connect의 걸음 수, 체중, 심박수, 수면 및 운동 기록에 대한 읽기 권한을 요청합니다. 허용한 항목만 읽으며 건강 기록을 쓰지는 않습니다.")
                        Text("수동 동기화는 선택한 Google 계정의 비공개 Drive 폴더 사용에 대한 별도 동의와 Google 권한을 받아 CSV를 업로드하거나, 별도 동의 후 선택한 로컬 폴더에 CSV를 저장합니다. 로컬 폴더의 파일은 다른 앱이나 사용자가 볼 수 있습니다. Drive 파일에 공유 권한을 추가하지 않습니다.")
                        Text("자동 동기화는 처음에는 꺼져 있으며 명시적으로 켠 경우에만 Drive를 사용합니다. 기기의 백그라운드 읽기 기능 지원, 별도 백그라운드 권한과 각 항목의 읽기 권한이 필요합니다. 걸음 수, 체중, 수면, 운동을 처리하며 자동 운동 심박 범위는 아직 확인되지 않아 차단 상태로 표시합니다. 실행은 기기 상태에 따라 지연되거나 완료되지 않을 수 있습니다.")
                        Text("선택한 계정에 연결하기 위한 최소 정보와 동기화 상태는 앱 전용 백업 제외 데이터베이스에 저장합니다. Google 접근 토큰과 처리 중인 건강 데이터 및 CSV 내용은 메모리에서 사용합니다.")
                        Text("자동 동기화를 끄면 이전 예약 작업을 무효화하고 취소하지만 이미 전송된 쓰기를 되돌리지는 않습니다. 끄기와 로컬 설정·동기화 상태 초기화는 시스템 권한 철회나 저장된 파일 삭제와 다릅니다. 로컬 이력 삭제도 설정 초기화와 별개입니다.")
                        Text("Health Connect 설정에서 항목별 또는 백그라운드 접근을 철회하고 Google 계정의 연결된 앱 설정에서 Google 권한을 철회할 수 있습니다. 초기화, 권한 철회 또는 앱 삭제로 Drive나 로컬 폴더의 CSV와 Health Connect 원본이 삭제되지는 않습니다. 내보낸 파일은 해당 저장 위치에서 직접 삭제해 주세요.")
                    }
                }
            }
        }
    }
}
