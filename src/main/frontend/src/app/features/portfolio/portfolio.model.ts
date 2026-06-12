export interface PortfolioAsset {
  id: string;
  ticker: string;
  assetGroup: string;
  shares: number;
  avgBuyPricePln: number;
  avgBuyPriceAssetCurrency: number;
  purchaseValuePln: number;
  purchaseValueAssetCurrency: number;
  purchaseSharePercent: number | null;
  currentPriceUsd: number | null;
  currentPricePln: number | null;
  currentValuePln: number | null;
  currentSharePercent: number | null;
  priceLastUpdatedAt: string | null;
  createdAt: string;
}

export type CreatePortfolioAssetDto = Omit<PortfolioAsset,
  'id' | 'createdAt' | 'currentPriceUsd' | 'currentPricePln' | 'currentValuePln' | 'currentSharePercent' | 'priceLastUpdatedAt'>;

export type UpdatePortfolioAssetDto = Partial<CreatePortfolioAssetDto>;

export interface PriceRefreshResponse {
  refreshed: boolean;
  assets: PortfolioAsset[];
}
