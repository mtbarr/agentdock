import { useState, useEffect } from 'react';
import { ACPBridge } from '../utils/bridge';

const lastFetchTime: Record<string, number> = {};
const cachedData: Record<string, string | null> = {};

export function useAdapterUsage(adapterId: string, ttlMs: number = 15000) {
  const [data, setData] = useState<string | null>(cachedData[adapterId] || null);

  useEffect(() => {
    const dispose = ACPBridge.onUsageData((e) => {
      if (e.detail.adapterId === adapterId && e.detail.json) {
        const newData = e.detail.json;
        const currentData = cachedData[adapterId];
        
        if (isBetterData(newData, currentData)) {
          cachedData[adapterId] = newData;
          setData(newData);
        }
      }
    });

    const tryFetch = () => {
      const now = Date.now();
      const lastFetch = lastFetchTime[adapterId] || 0;
      if (!cachedData[adapterId] || now - lastFetch > ttlMs) {
        lastFetchTime[adapterId] = now;
        console.log('Fetch usage: ' + adapterId);
        ACPBridge.fetchAdapterUsage(adapterId);
      }
    };

    tryFetch();
    const interval = setInterval(tryFetch, 240000); // 240 seconds background refresh

    return () => {
      dispose();
      clearInterval(interval);
    };
  }, [adapterId, ttlMs]);

  return data;
}

function isBetterData(newJson: string, currentJson: string | null | undefined): boolean {
  try {
    const newData = JSON.parse(newJson);
    if (!newData || typeof newData !== 'object') return false;

    // Define "rich" usage fields (actual numbers/percentages)
    const richFields = [
      'five_hour', 'seven_day', 'extra_usage', // Claude
      'rate_limit',                           // Codex
      'quota',                                // Gemini
      'usage'                                 // Generic
    ];

    const hasRichData = (obj: any) => richFields.some(field => obj[field] != null);
    
    // If new data is rich, always accept it
    if (hasRichData(newData)) return true;

    // If new data is NOT rich (e.g. only authType or error), 
    // only accept it if we don't have rich data in cache
    if (!currentJson) return true;
    
    try {
      const currentData = JSON.parse(currentJson);
      return !hasRichData(currentData);
    } catch {
      return true;
    }
  } catch {
    return false;
  }
}
