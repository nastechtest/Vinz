package com.vinz.music.data.lyrics

/**
 * Traditional Chinese characters in their simplified forms, for comparing (1.8.7 §6.1): YouTube Music often writes a
 * Mandarin song's title, artist and lyrics in traditional characters ("周杰倫", "簡單點") where the lyric sources write
 * simplified ones ("周杰伦", "简单点"). Only the common characters of song titles, names and lyrics; a character not
 * here stays as it is. Never shown: matching only.
 */
object HanForms {
    private const val PAIRS =
        "這这 說说 會会 時时 個个 們们 來来 為为 對对 過过 還还 沒没 麼么 愛爱 讓让 見见 開开 後后 裡里 裏里 與与 長长 無无 " +
        "點点 樣样 誰谁 東东 發发 邊边 頭头 經经 聽听 夢梦 現现 難难 運运 遠远 風风 門门 間间 問问 種种 從从 當当 給给 學学 " +
        "國国 實实 話话 語语 讀读 請请 謝谢 嗎吗 傷伤 淚泪 憶忆 戀恋 歡欢 樂乐 聲声 氣气 雙双 歲岁 隨随 雲云 亂乱 戲戏 陽阳 " +
        "陰阴 燈灯 黃黄 號号 華华 飛飞 魚鱼 鳥鸟 馬马 車车 紅红 綠绿 藍蓝 線线 紙纸 終终 結结 絕绝 總总 練练 續续 緣缘 繞绕 " +
        "網网 識识 誤误 該该 認认 記记 許许 論论 設设 計计 試试 詩诗 變变 歷历 曆历 選选 遞递 適适 遺遗 進进 連连 達达 違违 " +
        "鐘钟 錯错 錢钱 鏡镜 銀银 閃闪 閉闭 關关 隊队 陣阵 際际 隱隐 雖虽 雞鸡 離离 電电 靈灵 響响 頁页 順顺 願愿 顏颜 題题 " +
        "類类 顯显 飯饭 餘余 館馆 體体 髮发 鬧闹 麗丽 齊齐 龍龙 億亿 價价 儘尽 優优 兒儿 內内 兩两 別别 動动 勝胜 勢势 區区 " +
        "單单 卻却 員员 圍围 園园 圓圆 圖图 塊块 壓压 處处 備备 夠够 婦妇 媽妈 嬌娇 寫写 專专 將将 尋寻 導导 屬属 島岛 帶带 " +
        "幫帮 幾几 廣广 張张 強强 彈弹 歸归 復复 態态 憐怜 應应 懷怀 戰战 擁拥 擇择 擔担 據据 擊击 擺摆 換换 揮挥 損损 搖摇 " +
        "敗败 數数 斷断 晝昼 暫暂 曉晓 條条 楓枫 業业 極极 樹树 橋桥 機机 檢检 殘残 決决 沖冲 淺浅 測测 湧涌 滅灭 滿满 漢汉 " +
        "潔洁 濃浓 濕湿 灣湾 烏乌 煙烟 熱热 爭争 爾尔 牆墙 獨独 獲获 環环 產产 畫画 異异 療疗 盡尽 監监 盤盘 睜睁 確确 禮礼 " +
        "禱祷 穩稳 窮穷 競竞 筆笔 節节 範范 簡简 籠笼 緊紧 舊旧 艱艰 莊庄 葉叶 蓋盖 蘭兰 蟲虫 術术 衛卫 衝冲 補补 裝装 製制 " +
        "複复 規规 視视 親亲 覺觉 觀观 訊讯 訴诉 詞词 調调 談谈 證证 讚赞 豐丰 貝贝 負负 貨货 貴贵 買买 費费 資资 賣卖 質质 " +
        "贏赢 趕赶 跡迹 踐践 蹤踪 軌轨 軟软 輕轻 輪轮 輸输 轉转 辦办 農农 週周 錄录 鎖锁 閱阅 闊阔 陸陆 陳陈 隻只 雜杂 靜静 " +
        "須须 頂顶 預预 領领 頻频 飄飘 飲饮 驕骄 驗验 驚惊 鬥斗 鮮鲜 鳳凤 麥麦 齒齿 戶户 啟启 帥帅 倫伦 傑杰 謙谦 鄧邓 楊杨 " +
        "劉刘 吳吴 趙赵 孫孙 鄭郑 韓韩 馮冯 蕭萧 蘇苏 盧卢 蔣蒋 賈贾 閻阎 歐欧 譚谭 羅罗 簫箫 臺台 臉脸 腦脑 膽胆 聯联 聖圣 " +
        "腳脚 興兴 舉举 藝艺 藥药 虛虚 衆众 眾众 誕诞 謊谎 鬱郁 糾纠 紛纷 組组 細细 綁绑 維维 綿绵 緒绪 編编 縮缩 織织 繼继 " +
        "膩腻 遊游 遲迟 遙遥 鄉乡 醫医 釋释 鐵铁 閒闲 頓顿 顧顾 飽饱 餓饿 驅驱 髒脏 麵面 齡龄 嚮向 嘆叹 錶表 憂忧 層层 歎叹 " +
        "賞赏 賴赖 輩辈 擋挡 擠挤 懶懒 獻献 嚴严 壞坏 聽听 讓让 這这 誓誓 觸触 暈晕 傘伞 戀恋 嘗尝 塵尘 壯壮 彎弯 憑凭 擾扰"

    private val map: Map<Char, Char> = PAIRS.split(' ').filter { it.length == 2 && it[0] != it[1] }.associate { it[0] to it[1] }

    /** [s] with the traditional characters this knows in their simplified forms; the rest as it is. */
    fun simplified(s: String): String {
        if (s.none { it in map }) return s
        val b = StringBuilder(s.length)
        for (c in s) b.append(map[c] ?: c)
        return b.toString()
    }
}
