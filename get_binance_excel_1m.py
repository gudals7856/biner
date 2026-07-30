import ccxt
import pandas as pd
import time
from datetime import datetime

def fetch_all_klines_since(symbol='BTC/USDT', timeframe='1m', start_date='2026-01-01 00:00:00'):
    print(f"{symbol} {timeframe} 데이터를 {start_date} 부터 현재까지 수집합니다.")
    
    binance = ccxt.binance()
    all_ohlcv = []
    
    # 시작 날짜 문자열을 UTC 타임스탬프 (milliseconds)로 변환
    # ccxt의 parse8601 메소드는 ISO 8601 형식의 문자열을 UTC 타임스탬프로 변환해줍니다.
    since = binance.parse8601(start_date)
    
    while True:
        try:
            # 한 번에 최대 1000개씩 호출
            ohlcv = binance.fetch_ohlcv(symbol, timeframe, since=since, limit=1000)
            
            # 더 이상 가져올 데이터가 없으면 루프를 중단합니다.
            if not ohlcv:
                break
                
            all_ohlcv.extend(ohlcv)
            
            # 다음 데이터를 위해 since 시간 업데이트 (마지막으로 수집한 데이터의 타임스탬프)
            # +1을 해주는 이유는, 마지막 데이터를 중복으로 가져오는 것을 방지하기 위함입니다.
            since = ohlcv[-1][0] + 1
            
            print(f"현재 {len(all_ohlcv)}개 수집 완료... (가장 최근 데이터: {datetime.fromtimestamp(ohlcv[-1][0] / 1000)})")
            
            # 바이낸스 API 과부하 방지 (Rate Limit) - 0.5초 대기
            time.sleep(0.5) 
            
        except Exception as e:
            print(f"수집 중 에러 발생: {e}")
            break

    print(f"\n총 {len(all_ohlcv)}개의 데이터를 수집했습니다.")

    # 1. 수집된 데이터를 Pandas 데이터프레임으로 변환
    df = pd.DataFrame(all_ohlcv, columns=['Timestamp', 'Open', 'High', 'Low', 'Close', 'Volume'])
    
    # 2. 타임스탬프를 한국 시간(KST)으로 변환하여 새로운 컬럼에 저장
    df['Date(KST)'] = pd.to_datetime(df['Timestamp'], unit='ms') + pd.Timedelta(hours=9)
    
    # 3. 엑셀에 저장하기 편하도록 컬럼 순서 재배치 및 필요 없는 원본 Timestamp 삭제
    df = df[['Date(KST)', 'Open', 'High', 'Low', 'Close', 'Volume']]
    
    # 4. 오래된 데이터가 위로 오도록 오름차순 정렬
    df = df.sort_values(by='Date(KST)', ascending=True)
    
    # 5. 엑셀 파일로 저장
    filename = f"{symbol.replace('/', '')}_{timeframe}_since_{start_date.split(' ')[0]}.xlsx"
    print("엑셀 파일로 저장 중입니다...")
    
    df.to_excel(filename, index=False, engine='openpyxl')
    print(f"완료! 현재 폴더에 '{filename}' 파일이 저장되었습니다.")

# 실행: 비트코인 1분봉 데이터를 2026-01-01 부터 현재까지 수집
fetch_all_klines_since(symbol='BTC/USDT', timeframe='1m', start_date='2026-01-01 00:00:00')