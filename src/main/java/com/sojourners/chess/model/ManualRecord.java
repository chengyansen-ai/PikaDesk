package com.sojourners.chess.model;

import java.util.ArrayList;
import java.util.List;
import java.io.Serializable;

public class ManualRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer id;

    private Integer score;

    private String move;

    private String cnMove;

    private String remark;

    private int next;

    private List<ManualRecord> list = new ArrayList<>();

    public ManualRecord(Integer id, String move, String cnMove) {
        this.id = id;
        this.move = move;
        this.cnMove = cnMove;
    }
    public ManualRecord(Integer id, String name, Integer score) {
        this.id = id;
        this.cnMove = name;
        this.score = score;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public String getMove() {
        return move;
    }

    public void setMove(String move) {
        this.move = move;
    }

    public String getCnMove() {
        return cnMove;
    }

    public void setCnMove(String cnMove) {
        this.cnMove = cnMove;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public int getNext() {
        return next;
    }

    public void setNext(int next) {
        this.next = next;
    }

    public List<ManualRecord> getList() {
        return list;
    }

    public void setList(List<ManualRecord> list) {
        this.list = list;
    }
}
