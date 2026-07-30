import ccxt
import pandas as pd
import time

def fetch_bulk_klines(symbol='BTC/USDT', timeframe='1m', total_limit=5000):
    print(f"{symbol} {timeframe} 데이터를 대량으로 수집합니다. (목표: {total_limit}개)")
    
    binance = ccxt.binance()
    all_ohlcv = []
    
    # ccxt는 최근 데이터부터 가져옵니다. 
    # 과거 데이터를 대량으로 가져오려면 'since' 파라미터가 필요하므로, 목표 개수(분)만큼 과거의 타임스탬프를 계산합니다.
    now = binance.milliseconds()
    since = now - (total_limit * 60 * 1000) 
    
    while len(all_ohlcv) < total_limit:
        try:
            # 한 번에 최대 1000개씩 호출
            ohlcv = binance.fetch_ohlcv(symbol, timeframe, since=since, limit=1000)
            
            # 더 이상 가져올 데이터가 없으면 중단
            if not ohlcv:
                break
                
            all_ohlcv.extend(ohlcv)
            
            # 다음 데이터를 위해 since 시간 업데이트 (마지막으로 수집한 데이터의 1분 뒤)
            since = ohlcv[-1][0] + (60 * 1000)
            
            print(f"현재 {len(all_ohlcv)}개 수집 완료...")
            
            # 바이낸스 API 과부하 방지 (Rate Limit) - 0.5초 대기
            time.sleep(0.5) 
            
        except Exception as e:
            print(f"수집 중 에러 발생: {e}")
            break

    # 1. 수집된 데이터를 Pandas 데이터프레임으로 변환
    df = pd.DataFrame(all_ohlcv, columns=['Timestamp', 'Open', 'High', 'Low', 'Close', 'Volume'])
    
    # 2. 타임스탬프를 한국 시간(KST)으로 변환하여 새로운 컬럼에 저장
    df['Date(KST)'] = pd.to_datetime(df['Timestamp'], unit='ms') + pd.Timedelta(hours=9)
    
    # 3. 엑셀에 저장하기 편하도록 컬럼 순서 재배치 및 필요 없는 원본 Timestamp 삭제
    df = df[['Date(KST)', 'Open', 'High', 'Low', 'Close', 'Volume']]
    
    # 4. 최신 데이터가 위로 오도록 내림차순 정렬 (선택 사항)
    df = df.sort_values(by='Date(KST)', ascending=False)
    
    # 5. 엑셀 파일로 저장
    filename = f"{symbol.replace('/', '')}_{timeframe}_bulk_data.xlsx"
    print("엑셀 파일로 저장 중입니다...")
    
    df.to_excel(filename, index=False, engine='openpyxl')
    print(f"완료! 현재 폴더에 '{filename}' 파일이 저장되었습니다.")

# 실행: 비트코인 1분봉 5,000개 (약 3.5일치) 수집
# 더 많은 데이터를 원하시면 5000을 10000(약 7일), 43200(약 한 달) 등으로 수정하세요.
fetch_bulk_klines('BTC/USDT', '1m', 43200)