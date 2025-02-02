/*
 * Copyright (C) 2019-2021 FratikB0T Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.fratik.core.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.eventbus.EventBus;
import gg.amy.pgorm.PgMapper;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.slf4j.LoggerFactory;
import pl.fratik.core.event.DatabaseUpdateEvent;
import pl.fratik.core.manager.ManagerBazyDanych;

import java.util.*;

public class MemberDao implements Dao<MemberConfig> {

    private final PgMapper<MemberConfig> mapper;
    private final EventBus eventBus;

    public MemberDao(ManagerBazyDanych managerBazyDanych, EventBus eventBus) {
        if (managerBazyDanych == null) throw new IllegalStateException("managerBazyDanych == null");
        mapper = managerBazyDanych.getPgStore().mapSync(MemberConfig.class);
        this.eventBus = eventBus;
    }

    @Override
    public MemberConfig get(String id) {
        return mapper.load(id).orElseGet(() -> newObject(id));
    }

    public MemberConfig get(Member member) {
        return get(member.getUser().getId() + "." + member.getGuild().getId());
    }

    @Override
    public List<MemberConfig> getAll() {
        return mapper.loadAll();
    }

    @Override
    public void save(MemberConfig toCos) {
        ObjectMapper objMapper = new ObjectMapper();
        String jsoned;
        try { jsoned = objMapper.writeValueAsString(toCos); } catch (Exception ignored) { jsoned = toCos.toString(); }
        LoggerFactory.getLogger(getClass()).debug("Zmiana danych w DB: {} -> {} -> {}", toCos.getTableName(),
                toCos.getClass().getName(), jsoned);
        mapper.save(toCos);
        eventBus.post(new DatabaseUpdateEvent(toCos));
    }

    private MemberConfig newObject(String id) {
        return new MemberConfig(id.split("\\.")[0], id.split("\\.")[1]);
    }

    public Map<String, Long> getTopkaFratikcoinow(Guild serwer) {
        return getTopkaFratikcoinow(serwer, 0);
    }

    public Map<String, Long> getTopkaFratikcoinow(Guild serwer, long strona) {
        List<MemberConfig> data = getAll();
        data.sort(Comparator.comparingLong(MemberConfig::getFratikCoiny).reversed());
        LinkedHashMap<String, Long> map = new LinkedHashMap<>();

        for (int i = 0; i < data.size(); i++) {
            MemberConfig element = data.get(i);
            if (!element.getGuildId().equals(serwer.getId())) continue;
            String id = element.getUserId();
            long fratikcoiny = element.getFratikCoiny();
            if (id == null || fratikcoiny == 0) break;
            map.put(id, fratikcoiny);
        }

        LinkedHashMap<String, Long> map2 = new LinkedHashMap<>();

        List<String> keys = new ArrayList<>(map.keySet());
        for (int i = 0; i < map.size(); i++) {
            if (i < (strona * 10)) continue;
            if (map2.size() >= 10) break;
            String key = keys.get(i);
            map2.put(key, map.get(key));
        }

        return map2;
    }

}
